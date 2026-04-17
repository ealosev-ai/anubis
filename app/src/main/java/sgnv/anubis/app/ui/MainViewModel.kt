package sgnv.anubis.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.InstalledAppInfo
import sgnv.anubis.app.data.model.ManagedApp
import sgnv.anubis.app.data.model.NetworkInfo
import sgnv.anubis.app.data.repository.GroupsBackup
import sgnv.anubis.app.service.StealthState
import sgnv.anubis.app.service.StealthVpnService
import sgnv.anubis.app.service.VpnMonitorService
import sgnv.anubis.app.shizuku.FreezeMode
import sgnv.anubis.app.shizuku.ShizukuStatus
import sgnv.anubis.app.update.UpdateInfo
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AnubisApp
    val shizukuManager = app.shizukuManager
    private val vpnClientManager = app.vpnClientManager
    private val repository = app.appRepository
    private val orchestrator = app.orchestrator

    val stealthState: StateFlow<StealthState> = orchestrator.state
    val lastError: StateFlow<String?> = orchestrator.lastError
    val vpnActive: StateFlow<Boolean> = vpnClientManager.vpnActive
    val activeVpnClient: StateFlow<VpnClientType?> = vpnClientManager.activeVpnClient
    val activeVpnPackage: StateFlow<String?> = vpnClientManager.activeVpnPackage
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.status
    val frozenVersion: StateFlow<Long> = orchestrator.frozenVersion

    fun getVpnPermissionIntent(): Intent? = StealthVpnService.prepareVpn(getApplication())

    /**
     * Список установленных приложений + раскладка по группам вынесены
     * в AppListController — MainViewModel просто проксирует флоу и методы.
     */
    private val appListController = AppListController(
        context = application,
        repository = repository,
        shizuku = shizukuManager,
        scope = viewModelScope,
    )
    val installedApps: StateFlow<List<InstalledAppInfo>> = appListController.installedApps
    val localApps: StateFlow<List<ManagedApp>> = appListController.localApps
    val vpnOnlyApps: StateFlow<List<ManagedApp>> = appListController.vpnOnlyApps
    val launchVpnApps: StateFlow<List<ManagedApp>> = appListController.launchVpnApps

    /**
     * Выбор VPN-клиента, режим заморозки, настройки monitoring/hit-action
     * живут в SettingsController. MainViewModel только прокидывает StateFlow.
     */
    private val settingsController = SettingsController(
        context = application,
        shizuku = shizukuManager,
        vpnClientManager = vpnClientManager,
        scope = viewModelScope,
    )
    val selectedVpnClient: StateFlow<SelectedVpnClient> = settingsController.selectedVpnClient
    val installedVpnClients: StateFlow<List<VpnClientType>> = settingsController.installedVpnClients
    val backgroundMonitoring: StateFlow<Boolean> = settingsController.backgroundMonitoring
    val freezeMode: StateFlow<FreezeMode> = settingsController.freezeMode
    val hitActionMode: StateFlow<String> = settingsController.hitActionMode
    val auditBackground: StateFlow<Boolean> = settingsController.auditBackground
    val auditDecoyWithBackground: StateFlow<Boolean> = settingsController.auditDecoyWithBackground

    private val _networkInfo = MutableStateFlow<NetworkInfo?>(null)
    val networkInfo: StateFlow<NetworkInfo?> = _networkInfo

    private val _networkLoading = MutableStateFlow(false)
    val networkLoading: StateFlow<Boolean> = _networkLoading

    /** Всё связанное с self-update вынесено в UpdateController. */
    private val updateController = UpdateController(application, viewModelScope)
    val updateInfo: StateFlow<UpdateInfo?> = updateController.updateInfo
    val updateCheckEnabled: StateFlow<Boolean> = updateController.updateCheckEnabled
    val updateCheckInProgress: StateFlow<Boolean> = updateController.updateCheckInProgress
    val updateSource: StateFlow<String> = updateController.updateSource

    private val _dangerousAppWarning = MutableStateFlow<String?>(null)
    val dangerousAppWarning: StateFlow<String?> = _dangerousAppWarning

    private val _resetCompleted = MutableSharedFlow<Int>()
    val resetCompleted: SharedFlow<Int> = _resetCompleted

    init {
        // Мониторинг VPN стартует в AnubisApp.onCreate — здесь не дублируем.
        // refreshVpnClients/loadSelectedClient/loadBackgroundMonitoring — теперь в SettingsController.init.
        loadInstalledApps()
        loadGroupedApps()
        scheduleAutoFreeze()
        observeVpnState()
        checkDangerousApps()
        updateController.scheduleAutoCheck()
    }

    /** Watch VPN state changes — auto-freeze, sync state, refresh network */
    private fun observeVpnState() {
        viewModelScope.launch {
            var prevActive = vpnClientManager.vpnActive.value
            vpnClientManager.vpnActive.collect { active ->
                if (active != prevActive) {
                    prevActive = active

                    if (active) {
                        // VPN turned ON (possibly outside Anubis) — freeze LOCAL apps
                        orchestrator.freezeOnly()
                        VpnMonitorService.start(getApplication())
                    } else {
                        // VPN turned OFF — freeze VPN_ONLY apps
                        orchestrator.freezeVpnOnly()
                    }

                    orchestrator.syncState()

                    if (active && orchestrator.lastError.value?.contains("вручную") == true) {
                        orchestrator.clearError()
                    }

                    delay(500)
                    refreshNetworkInfo()
                }
            }
        }
    }

    fun toggleStealth() {
        viewModelScope.launch {
            if (stealthState.value == StealthState.DISABLED) {
                orchestrator.enable(settingsController.selectedVpnClient.value)
                if (orchestrator.state.value == StealthState.ENABLED) {
                    VpnMonitorService.start(getApplication())
                }
            } else if (stealthState.value == StealthState.ENABLED) {
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                val detectedClient = vpnClientManager.activeVpnClient.value
                val clientToStop = if (detectedClient != null) SelectedVpnClient.fromKnown(detectedClient)
                    else vpnClientManager.activeVpnPackage.value?.let { SelectedVpnClient.fromPackage(it) }
                    ?: settingsController.selectedVpnClient.value
                orchestrator.disable(clientToStop, detectedPkg)
                if (orchestrator.state.value == StealthState.DISABLED) {
                    VpnMonitorService.stop(getApplication())
                }
            }
        }
    }

    /**
     * «Рабочее окружение» — одна кнопка разворачивает всё окружение через VPN.
     * Подробности в [StealthOrchestrator.enableWorkEnvironment]. Если state
     * сейчас DISABLED → enableWorkEnvironment; если ENABLED — disableWorkEnvironment.
     */
    fun toggleWorkEnvironment() {
        viewModelScope.launch {
            if (stealthState.value == StealthState.DISABLED) {
                orchestrator.enableWorkEnvironment(settingsController.selectedVpnClient.value)
                if (orchestrator.state.value == StealthState.ENABLED) {
                    VpnMonitorService.start(getApplication())
                }
            } else if (stealthState.value == StealthState.ENABLED) {
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                val detectedClient = vpnClientManager.activeVpnClient.value
                val clientToStop = if (detectedClient != null) SelectedVpnClient.fromKnown(detectedClient)
                    else detectedPkg?.let { SelectedVpnClient.fromPackage(it) }
                    ?: settingsController.selectedVpnClient.value
                orchestrator.disableWorkEnvironment(clientToStop, detectedPkg)
                if (orchestrator.state.value == StealthState.DISABLED) {
                    VpnMonitorService.stop(getApplication())
                }
            }
        }
    }

    fun launchWithVpn(packageName: String) {
        viewModelScope.launch {
            orchestrator.launchWithVpn(packageName, settingsController.selectedVpnClient.value)
            if (orchestrator.state.value == StealthState.ENABLED) {
                VpnMonitorService.start(getApplication())
            }
        }
    }

    fun launchLocal(packageName: String) {
        viewModelScope.launch {
            val detectedPkg = vpnClientManager.activeVpnPackage.value
            val detectedClient = vpnClientManager.activeVpnClient.value
            val clientToStop = if (detectedClient != null) SelectedVpnClient.fromKnown(detectedClient)
                else detectedPkg?.let { SelectedVpnClient.fromPackage(it) }
                ?: settingsController.selectedVpnClient.value
            orchestrator.launchLocal(packageName, clientToStop, detectedPkg)
            if (orchestrator.state.value == StealthState.DISABLED) {
                VpnMonitorService.stop(getApplication())
            }
        }
    }

    fun toggleAppFrozen(packageName: String) {
        viewModelScope.launch {
            orchestrator.toggleAppFrozen(packageName)
            loadGroupedApps()
        }
    }

    fun isAppFrozen(packageName: String): Boolean = appListController.isAppFrozen(packageName)
    fun removeFromGroup(packageName: String) = appListController.removeFromGroup(packageName)
    fun createShortcut(packageName: String) = appListController.createShortcut(packageName)
    fun cycleAppGroup(packageName: String) = appListController.cycleAppGroup(packageName)
    fun autoSelectRestricted() = appListController.autoSelectRestricted()

    fun unfreezeAllAndClear() {
        viewModelScope.launch {
            val allManaged = repository.getAllManagedPackages()
            var unfrozenCount = 0
            for (pkg in allManaged) {
                if (shizukuManager.isAppFrozen(pkg)) {
                    shizukuManager.unfreezeApp(pkg)
                    unfrozenCount++
                }
                repository.removeApp(pkg)
            }
            loadInstalledApps()
            loadGroupedApps()
            orchestrator.syncState()
            _resetCompleted.emit(unfrozenCount)
        }
    }

    /** Emergency: scan PM for all user-disabled apps and unfreeze them.
     *  Covers the case when Anubis was reinstalled and DB is empty but apps are still frozen. */
    fun unfreezeAllUserDisabled() {
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            val disabled = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                    .filter { !it.enabled && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { it.packageName }
            }
            var unfrozenCount = 0
            for (pkg in disabled) {
                shizukuManager.unfreezeApp(pkg)
                unfrozenCount++
            }
            // Also clear DB so orchestrator state stays consistent
            for (pkg in repository.getAllManagedPackages()) {
                repository.removeApp(pkg)
            }
            loadInstalledApps()
            loadGroupedApps()
            orchestrator.syncState()
            _resetCompleted.emit(unfrozenCount)
        }
    }

    fun loadInstalledApps() = appListController.loadInstalledApps()
    fun loadGroupedApps() = appListController.loadGroupedApps()

    fun selectVpnClient(client: SelectedVpnClient) = settingsController.selectVpnClient(client)
    fun isVpnClientEnabled(packageName: String): Boolean = settingsController.isVpnClientEnabled(packageName)

    suspend fun exportGroupsJson(): String = GroupsBackup.export(repository)

    /**
     * @param replaceAll если true — сначала чистим все managed_apps, потом применяем.
     *                   false (merge) — только обновляем существующие + добавляем новые.
     * @return количество применённых записей, или -1 при битом JSON.
     */
    suspend fun importGroupsJson(json: String, replaceAll: Boolean): Int {
        val n = if (replaceAll) GroupsBackup.replaceAll(repository, json)
        else GroupsBackup.import(repository, json)
        if (n >= 0) {
            // Перечитаем группы в _localApps / _vpnOnlyApps / _launchVpnApps.
            loadGroupedApps()
        }
        return n
    }

    fun setUpdateSource(source: String) = updateController.setSource(source)

    fun setHitActionMode(mode: String) = settingsController.setHitActionMode(mode)
    fun setFreezeMode(mode: FreezeMode) = settingsController.setFreezeMode(mode)
    fun setBackgroundMonitoring(enabled: Boolean) = settingsController.setBackgroundMonitoring(enabled)
    fun setAuditBackground(enabled: Boolean) = settingsController.setAuditBackground(enabled)
    fun setAuditDecoyWithBackground(enabled: Boolean) = settingsController.setAuditDecoyWithBackground(enabled)

    fun dismissDangerousAppWarning() {
        _dangerousAppWarning.value = null
    }

    private fun checkDangerousApps() {
        val dominated = mapOf(
            "ru.dahl.messenger" to "https://dontusetelega.lol/analysis"
        )
        val pm = getApplication<Application>().packageManager
        for ((pkg, url) in dominated) {
            try {
                pm.getApplicationInfo(pkg, 0)
                _dangerousAppWarning.value = url
                return
            } catch (_: Exception) {}
        }
    }

    fun requestShizukuPermission() {
        if (shizukuManager.isAvailable()) {
            shizukuManager.requestPermission()
        }
    }

    fun refreshNetworkInfo() {
        viewModelScope.launch {
            _networkLoading.value = true
            _networkInfo.value = fetchNetworkInfo()
            _networkLoading.value = false
        }
    }

    fun onResume() {
        shizukuManager.refreshStatus()
        if (shizukuManager.status.value == ShizukuStatus.READY) {
            shizukuManager.bindUserService()
        }
        vpnClientManager.refreshVpnState()
        orchestrator.syncState()
        refreshVpnClients()
        viewModelScope.launch { vpnClientManager.detectActiveVpnClient() }
        loadInstalledApps()
        loadGroupedApps()
        scheduleAutoFreeze()
    }

    private fun scheduleAutoFreeze() {
        viewModelScope.launch {
            // Shizuku is initialized in Application.onCreate — just bind UserService
            if (shizukuManager.status.value == ShizukuStatus.READY) {
                shizukuManager.bindUserService()
                delay(200)
                vpnClientManager.detectActiveVpnClient()
            } else {
                // First launch or Shizuku not yet ready — brief wait
                repeat(5) {
                    delay(300)
                    if (shizukuManager.status.value == ShizukuStatus.READY) {
                        shizukuManager.bindUserService()
                        delay(200)
                        vpnClientManager.detectActiveVpnClient()
                        return@repeat
                    }
                }
            }

            if (vpnClientManager.vpnActive.value
                && stealthState.value == StealthState.DISABLED
                && shizukuManager.status.value == ShizukuStatus.READY
            ) {
                orchestrator.freezeOnly()
                if (orchestrator.state.value == StealthState.ENABLED) {
                    VpnMonitorService.start(getApplication())
                }
            }
        }
    }

    private suspend fun fetchNetworkInfo(): NetworkInfo? = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val json = URL("https://ipinfo.io/json").readText()
            val pingMs = System.currentTimeMillis() - start
            val obj = JSONObject(json)
            NetworkInfo(
                ip = obj.optString("ip", "?"),
                country = obj.optString("country", ""),
                city = obj.optString("city", ""),
                org = obj.optString("org", ""),
                pingMs = pingMs
            )
        } catch (e: Exception) {
            try {
                val start = System.currentTimeMillis()
                val ip = URL("https://api.ipify.org").readText().trim()
                val pingMs = System.currentTimeMillis() - start
                NetworkInfo(ip = ip, pingMs = pingMs)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun refreshVpnClients() = settingsController.refreshVpnClients()

    fun setUpdateCheckEnabled(enabled: Boolean) = updateController.setEnabled(enabled)
    fun checkForUpdatesNow() = updateController.checkNow()
    fun dismissUpdateDialog() = updateController.dismiss()
    fun skipCurrentUpdate() = updateController.skipCurrent()

    override fun onCleared() {
        // Мониторинг VPN живёт на Application-уровне — ViewModel больше его не дергает.
        super.onCleared()
    }
}
