package sgnv.anubis.app.service

import android.content.Context
import sgnv.anubis.app.data.DefaultRestrictedApps
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.PackageGroupsReader
import sgnv.anubis.app.shizuku.FreezeActions
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnControlMode
import sgnv.anubis.app.vpn.VpnControls
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Core logic:
 *
 * LOCAL ("No VPN") and VPN_ONLY are always frozen by default.
 * They are only unfrozen when the user explicitly launches them from the home screen.
 *
 * enable (VPN ON):  freeze LOCAL group
 * disable (VPN OFF): freeze VPN_ONLY group
 *
 * launchWithVpn: ensure VPN is on + restricted frozen → unfreeze this app → launch
 * launchLocal:     ensure VPN is off + vpn_only frozen → unfreeze this app → launch
 */
class StealthOrchestrator(
    private val context: Context,
    private val shizukuManager: FreezeActions,
    private val vpnClientManager: VpnControls,
    private val repository: PackageGroupsReader,
    /**
     * Как поднять decoy VPN для revoke'а чужого туннеля (step 2 трёх-фазного stop).
     * По умолчанию — [StealthVpnService.disconnect], но тесты подсовывают no-op
     * чтобы не трогать VpnService framework.
     */
    private val decoyDisconnect: (Context) -> Unit = StealthVpnService::disconnect,
) {
    private val _state = MutableStateFlow(StealthState.DISABLED)
    val state: StateFlow<StealthState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun clearError() { _lastError.value = null }

    /**
     * Sync stealth state from reality.
     * Called on onResume — handles state changes made by ShortcutActivity or TileService.
     */
    fun syncState() {
        val vpnActive = vpnClientManager.vpnActive.value
        // If VPN is on, we're in stealth mode (spies should be frozen)
        // If VPN is off, stealth is disabled
        val newState = if (vpnActive) StealthState.ENABLED else StealthState.DISABLED
        if (_state.value != StealthState.ENABLING && _state.value != StealthState.DISABLING) {
            _state.value = newState
        }
    }

    /** Version counter — incremented on any freeze/unfreeze to trigger UI refresh */
    private val _frozenVersion = MutableStateFlow(0L)
    val frozenVersion: StateFlow<Long> = _frozenVersion

    /**
     * Прогресс текущей batch-операции (freeze/unfreeze группы). null = нет активной
     * операции. UI рисует линейку и "done / total", пока это не null.
     */
    data class BatchProgress(val done: Int, val total: Int, val label: String)
    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress: StateFlow<BatchProgress?> = _batchProgress

    /**
     * Enable stealth (VPN ON): freeze LOCAL apps, start VPN.
     * VPN_ONLY stays frozen — they are only unfrozen by explicit launch.
     */
    suspend fun enable(client: SelectedVpnClient) {
        _lastError.value = null
        _state.value = StealthState.ENABLING

        if (!checkShizuku()) return

        if (shizukuManager.isAppFrozen(client.packageName)) {
            fail("VPN-клиент ${client.displayName} заморожен!")
            return
        }

        freezeGroup(AppGroup.LOCAL)

        vpnClientManager.startVPN(client)

        bumpVersion()

        if (client.controlMode == VpnControlMode.MANUAL) {
            _lastError.value = "Подключите VPN вручную в ${client.displayName}"
        }

        _state.value = StealthState.ENABLED
    }

    /**
     * Disable stealth (VPN OFF): stop VPN, freeze VPN_ONLY apps.
     * LOCAL stays frozen — they are only unfrozen by explicit launch.
     */
    suspend fun disable(client: SelectedVpnClient, detectedPackage: String?) {
        _lastError.value = null
        _state.value = StealthState.DISABLING

        if (vpnClientManager.vpnActive.value) {
            if (!stopVpn(client, detectedPackage)) {
                _lastError.value = "Не удалось отключить VPN. Приложения НЕ разморожены."
                _state.value = StealthState.ENABLED
                return
            }
        }

        freezeGroup(AppGroup.VPN_ONLY)
        bumpVersion()
        _state.value = StealthState.DISABLED
    }

    /**
     * VPN already active on app start — just freeze LOCAL.
     */
    suspend fun freezeOnly() {
        _lastError.value = null
        if (!checkShizuku()) return
        freezeGroup(AppGroup.LOCAL)
        bumpVersion()
        _state.value = StealthState.ENABLED
    }

    /**
     * VPN turned OFF — freeze VPN_ONLY group.
     */
    suspend fun freezeVpnOnly() {
        if (!checkShizuku()) return
        freezeGroup(AppGroup.VPN_ONLY)
        bumpVersion()
        _state.value = StealthState.DISABLED
    }

    /**
     * Launch app from LAUNCH_VPN or VPN_ONLY group:
     * freeze LOCAL → start VPN → unfreeze this app → launch.
     */
    suspend fun launchWithVpn(packageName: String, vpnClient: SelectedVpnClient) {
        _lastError.value = null

        if (_state.value != StealthState.ENABLED) {
            enable(vpnClient)
            if (_state.value != StealthState.ENABLED) return
        }

        if (shizukuManager.isAppFrozen(packageName)) {
            shizukuManager.unfreezeApp(packageName)
            bumpVersion()
        }

        vpnClientManager.launchApp(packageName)
    }

    /**
     * Launch LOCAL app: stop VPN → freeze VPN_ONLY → unfreeze app → launch.
     */
    suspend fun launchLocal(
        packageName: String,
        vpnClient: SelectedVpnClient,
        detectedPackage: String?
    ) {
        _lastError.value = null

        if (_state.value == StealthState.ENABLED || vpnClientManager.vpnActive.value) {
            disable(vpnClient, detectedPackage)
            if (vpnClientManager.vpnActive.value) {
                _lastError.value = "Не удалось отключить VPN. Приложение не запущено."
                return
            }
        }

        if (shizukuManager.isAppFrozen(packageName)) {
            shizukuManager.unfreezeApp(packageName)
            bumpVersion()
        }

        vpnClientManager.launchApp(packageName)
    }

    /**
     * Work Environment — полный цикл «режим работы через VPN» одной кнопкой.
     *
     * ВКЛ (enableWorkEnvironment):
     *   1. freeze LOCAL — банки уснули ДО того как VPN поднялся.
     *   2. start VPN + ждём vpnActive=true.
     *   3. unfreeze VPN_ONLY + LAUNCH_VPN — иконки вернулись на launcher.
     *   4. launchApp() каждое из них с задержкой 500ms — поднимаем процессы
     *      (push-уведомления Telegram, подгрузка YouTube, etc).
     *
     * В отличие от обычного [enable] — этот метод ещё и размораживает+стартует
     * VPN-группу, «разворачивает рабочее окружение» оптом вместо launch иконок
     * по одной.
     */
    suspend fun enableWorkEnvironment(client: SelectedVpnClient) {
        _lastError.value = null
        _state.value = StealthState.ENABLING

        if (!checkShizuku()) return

        if (shizukuManager.isAppFrozen(client.packageName)) {
            fail("VPN-клиент ${client.displayName} заморожен!")
            return
        }

        // 1 — freeze LOCAL (банки уснули)
        freezeGroup(AppGroup.LOCAL)

        // 2 — start VPN + ждём фактического поднятия (MANUAL не попадает сюда —
        // там vpnActive сам не выставится, сразу идём в unfreeze с warning)
        vpnClientManager.startVPN(client)

        if (client.controlMode == VpnControlMode.MANUAL) {
            _lastError.value = "Подключите VPN вручную в ${client.displayName}, " +
                "потом заново нажмите «Рабочее окружение»"
            _state.value = StealthState.DISABLED
            return
        }

        if (!waitForVpnOn(timeoutMs = 5_000)) {
            _lastError.value = "VPN не поднялся за 5с — приложения НЕ запущены"
            _state.value = StealthState.ENABLED  // VPN pending, но LOCAL заморожены
            return
        }

        // 3 — unfreeze VPN-группу (только теперь, когда тоннель готов)
        val vpnGroup = repository.getPackagesByGroup(AppGroup.VPN_ONLY) +
            repository.getPackagesByGroup(AppGroup.LAUNCH_VPN)
        unfreezeAll(vpnGroup)
        bumpVersion()

        // 4 — launch каждое. delay(300) чтобы Android не отменил предыдущий
        // startActivity когда сразу прилетает следующий NEW_TASK. 300ms оптимум:
        // меньше — ActivityManager сливает NEW_TASK-intent'ы, больше — сумма
        // задержек на 15+ пакетов становится заметной (7с+ только на launch).
        for (pkg in vpnGroup) {
            if (shizukuManager.isAppInstalled(pkg)) {
                vpnClientManager.launchApp(pkg)
                delay(300)
            }
        }

        _state.value = StealthState.ENABLED
    }

    /**
     * Work Environment OFF: freeze VPN-группу → stop VPN → unfreeze LOCAL.
     *
     * Порядок важен для безопасности: замораживаем Telegram/YouTube ДО падения
     * VPN — их процессы умирают, фоновые запросы не успеют уйти через открытый
     * канал. Потом 3-phase stop. В конце размораживаем LOCAL чтобы пользователь
     * мог запускать банки обычным тапом по иконке.
     */
    suspend fun disableWorkEnvironment(
        client: SelectedVpnClient,
        detectedPackage: String?,
    ) {
        _lastError.value = null
        _state.value = StealthState.DISABLING

        if (!checkShizuku()) return

        // 1 — freeze VPN-группа (процессы убиты, push-синхронизация не пойдёт)
        freezeGroup(AppGroup.VPN_ONLY)
        freezeGroup(AppGroup.LAUNCH_VPN)
        bumpVersion()

        // 2 — stop VPN (3-phase)
        if (vpnClientManager.vpnActive.value) {
            if (!stopVpn(client, detectedPackage)) {
                _lastError.value = "Не удалось отключить VPN. LOCAL остался заморожен."
                _state.value = StealthState.ENABLED
                return
            }
        }

        // 3 — unfreeze LOCAL (банки доступны в launcher)
        val localGroup = repository.getPackagesByGroup(AppGroup.LOCAL)
        unfreezeAll(localGroup)
        bumpVersion()

        _state.value = StealthState.DISABLED
    }

    private suspend fun unfreezeAll(packages: Set<String>) = coroutineScope {
        val total = packages.size
        if (total == 0) return@coroutineScope
        _batchProgress.value = BatchProgress(done = 0, total = total, label = "Разморозка")
        val sem = Semaphore(permits = FREEZE_PARALLELISM)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        packages.map { pkg ->
            async {
                sem.withPermit {
                    if (shizukuManager.isAppInstalled(pkg) && shizukuManager.isAppFrozen(pkg)) {
                        shizukuManager.unfreezeApp(pkg)
                        delay(INTER_OP_DELAY_MS)
                    }
                    _batchProgress.value = BatchProgress(
                        done = counter.incrementAndGet(), total = total, label = "Разморозка",
                    )
                }
            }
        }.awaitAll()
        _batchProgress.value = null
    }

    /**
     * Manual freeze/unfreeze from context menu.
     */
    suspend fun toggleAppFrozen(packageName: String) {
        if (!checkShizuku()) return
        if (shizukuManager.isAppFrozen(packageName)) {
            shizukuManager.unfreezeApp(packageName)
        } else {
            shizukuManager.freezeApp(packageName)
        }
        bumpVersion()
    }

    // --- Helpers ---

    private fun bumpVersion() {
        _frozenVersion.value++
    }

    private suspend fun stopVpn(client: SelectedVpnClient, detectedPackage: String?): Boolean {
        // Step 1: API stop — only for SEPARATE mode (explicit stop command).
        // TOGGLE is unreliable for stop (can re-enable immediately), skip to dummy VPN.
        if (client.controlMode == VpnControlMode.SEPARATE) {
            vpnClientManager.stopVPN(client)
            if (waitForVpnOff(3000)) return true
        }

        // Step 2: Dummy VPN — take over as VPN, system kills theirs
        decoyDisconnect(context)
        if (waitForVpnOff(2000)) return true

        // Step 3: Force-stop the detected VPN app
        val pkg = detectedPackage ?: client.packageName
        shizukuManager.forceStopApp(pkg)
        return waitForVpnOff(2000)
    }

    private fun checkShizuku(): Boolean {
        if (!shizukuManager.isAvailable()) { fail("Shizuku не запущен."); return false }
        if (!shizukuManager.hasPermission()) { fail("Нет разрешения Shizuku."); return false }
        return true
    }

    private suspend fun freezeGroup(group: AppGroup) = coroutineScope {
        // Safety net: даже если пакет из neverRestrict (клавиатуры и пр.) как-то
        // залип в этой группе (пользователь добавил вручную / legacy-данные),
        // мы его НЕ морозим. Без IME человек не сможет печатать — freeze
        // клавиатуры = softbrick UX на VPN-on.
        val packages = repository.getPackagesByGroup(group)
            .filter { it !in DefaultRestrictedApps.neverRestrict }
        val total = packages.size
        if (total == 0) return@coroutineScope
        // PARALLELISM=2 + delay(100ms) между операциями: на Honor MagicOS параллельные
        // `pm disable-user` шлют PACKAGE_REMOVED broadcasts, UniHome-лаунчер не
        // успевает переваривать — получается ANR. 2 в параллель с паузой
        // удерживают штатный ритм перерисовки launcher grid'а.
        _batchProgress.value = BatchProgress(done = 0, total = total, label = "Заморозка")
        val sem = Semaphore(permits = FREEZE_PARALLELISM)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        packages.map { pkg ->
            async {
                sem.withPermit {
                    if (shizukuManager.isAppInstalled(pkg) && !shizukuManager.isAppFrozen(pkg)) {
                        shizukuManager.freezeApp(pkg)
                        delay(INTER_OP_DELAY_MS)
                    }
                    _batchProgress.value = BatchProgress(
                        done = counter.incrementAndGet(), total = total, label = "Заморозка",
                    )
                }
            }
        }.awaitAll()
        _batchProgress.value = null
    }

    private suspend fun waitForVpnOff(timeoutMs: Long): Boolean {
        val steps = (timeoutMs / 200).toInt()
        repeat(steps) {
            delay(200)
            vpnClientManager.refreshVpnState()
            if (!vpnClientManager.vpnActive.value) return true
        }
        return false
    }

    private suspend fun waitForVpnOn(timeoutMs: Long): Boolean {
        val steps = (timeoutMs / 200).toInt()
        repeat(steps) {
            delay(200)
            vpnClientManager.refreshVpnState()
            if (vpnClientManager.vpnActive.value) return true
        }
        return false
    }

    private fun fail(message: String) {
        _lastError.value = message
        _state.value = StealthState.DISABLED
    }

    private companion object {
        const val FREEZE_PARALLELISM = 2
        /** Пауза между freeze/unfreeze операциями, чтобы Honor launcher переварил PACKAGE_REMOVED broadcast. */
        const val INTER_OP_DELAY_MS = 100L
    }
}

enum class StealthState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING
}
