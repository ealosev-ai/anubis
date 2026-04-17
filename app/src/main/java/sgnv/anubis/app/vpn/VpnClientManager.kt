package sgnv.anubis.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import sgnv.anubis.app.shizuku.ShellExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class VpnClientManager(
    private val context: Context,
    private val shell: ShellExec,
) : VpnControls {

    protected val _vpnActive = MutableStateFlow(false)
    override val vpnActive: StateFlow<Boolean> = _vpnActive

    private val _activeVpnClient = MutableStateFlow<VpnClientType?>(null)
    val activeVpnClient: StateFlow<VpnClientType?> = _activeVpnClient

    /** Raw package name of active VPN app (even if not in our known list) */
    private val _activeVpnPackage = MutableStateFlow<String?>(null)
    val activeVpnPackage: StateFlow<String?> = _activeVpnPackage

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Собственный scope — fire-and-forget detect раньше сыпался в GlobalScope-подобный
    // CoroutineScope(Dispatchers.IO), не отменялся при stopMonitoringVpn() и мог
    // записать stale данные уже после того как _vpnActive сброшен.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var detectJob: Job? = null
    private val detectMutex = Mutex()

    fun getInstalledClients(): List<VpnClientType> {
        return VpnClientType.entries.filter { isInstalled(it) }
    }

    fun isInstalled(type: VpnClientType): Boolean {
        return try {
            context.packageManager.getApplicationInfo(type.packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Check if the app is installed AND enabled (not frozen/disabled) */
    fun isEnabled(type: VpnClientType): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(type.packageName, 0)
            info.enabled
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start VPN via Shizuku shell command.
     * For TOGGLE: sends toggle only if VPN is currently off.
     * For MANUAL: just opens the app.
     */
    open override suspend fun startVPN(client: SelectedVpnClient) {
        val knownType = client.knownType
        if (knownType != null) {
            val control = VpnClientControls.getControl(knownType)
            when (control.mode) {
                VpnControlMode.SEPARATE -> {
                    val cmd = control.startCommand ?: return
                    val result = shell.execCommand(*cmd)
                    if (result.isFailure) {
                        Log.w(TAG, "Start failed for ${client.displayName}", result.exceptionOrNull())
                        launchApp(client.packageName)
                    }
                }
                VpnControlMode.TOGGLE -> {
                    if (!_vpnActive.value) {
                        val cmd = control.startCommand ?: return
                        val result = shell.execCommand(*cmd)
                        if (result.isFailure) {
                            Log.w(TAG, "Toggle-start failed for ${client.displayName}", result.exceptionOrNull())
                            launchApp(client.packageName)
                        }
                    }
                }
                VpnControlMode.MANUAL -> launchApp(client.packageName)
            }
        } else {
            // Custom / unknown client — just open it
            launchApp(client.packageName)
        }
    }

    open override suspend fun stopVPN(client: SelectedVpnClient) {
        val knownType = client.knownType
        if (knownType != null) {
            val control = VpnClientControls.getControl(knownType)
            when (control.mode) {
                VpnControlMode.SEPARATE -> {
                    val cmd = control.stopCommand ?: return
                    val result = shell.execCommand(*cmd)
                    if (result.isFailure) Log.w(TAG, "Stop failed for ${client.displayName}", result.exceptionOrNull())
                }
                VpnControlMode.TOGGLE -> {
                    if (_vpnActive.value) {
                        val cmd = control.startCommand ?: return
                        val result = shell.execCommand(*cmd)
                        if (result.isFailure) Log.w(TAG, "Toggle-stop failed for ${client.displayName}", result.exceptionOrNull())
                    }
                }
                VpnControlMode.MANUAL -> { /* caller handles via force-stop */ }
            }
        }
        // For custom clients: caller handles via force-stop
    }

    open override fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }

    open fun startMonitoringVpn() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _vpnActive.value = isVpnCurrentlyActive(cm)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _vpnActive.value = true
                // Отменяем предыдущий detect, если он ещё бежит — новый VPN
                // появился, старое значение уже неактуально.
                scope.launch {
                    detectJob?.cancelAndJoin()
                    detectJob = scope.launch { detectActiveVpnClient() }
                }
            }

            override fun onLost(network: Network) {
                val stillActive = isVpnCurrentlyActive(cm)
                _vpnActive.value = stillActive
                if (!stillActive) {
                    // Гонка: detectJob мог уже прочитать pkg и стоять перед записью.
                    // Отменяем его, чтобы не перезаписал null обратно на stale-значение.
                    scope.launch {
                        detectJob?.cancelAndJoin()
                        _activeVpnClient.value = null
                        _activeVpnPackage.value = null
                    }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register VPN network callback", e)
        }
    }

    fun stopMonitoringVpn() {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
            networkCallback = null
        }
        detectJob?.cancel()
        detectJob = null
    }

    /** Освободить все корутины (shutdown процесса). */
    fun shutdown() {
        stopMonitoringVpn()
        scope.cancel()
    }

    override fun refreshVpnState() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _vpnActive.value = isVpnCurrentlyActive(cm)
    }

    /**
     * Detect which VPN client is currently providing the active VPN connection.
     * Tries multiple strategies — first success wins.
     */
    suspend fun detectActiveVpnClient(): VpnClientType? = detectMutex.withLock {
        if (!_vpnActive.value) {
            _activeVpnClient.value = null
            _activeVpnPackage.value = null
            return@withLock null
        }

        val pkg = getVpnOwnerPackage()

        // Между началом резолва и сейчас VPN мог отвалиться — не пишем stale.
        if (!_vpnActive.value) {
            _activeVpnClient.value = null
            _activeVpnPackage.value = null
            return@withLock null
        }

        _activeVpnPackage.value = pkg
        val client = if (pkg != null) VpnClientType.fromPackageName(pkg) else null
        _activeVpnClient.value = client
        client
    }

    /**
     * Multi-strategy VPN owner detection:
     * 1. Shell: dumpsys connectivity — grep "Transports: VPN" and extract OwnerUid
     * 2. Shell: check which known client has a foreground service
     */
    private suspend fun getVpnOwnerPackage(): String? {
        // Strategy 1: dumpsys connectivity — authoritative, works for ANY app
        val dumpsysResult = getVpnOwnerByDumpsys()
        if (dumpsysResult != null) return dumpsysResult

        // Strategy 2: check foreground services of known clients
        val fgResult = getVpnOwnerByForegroundService()
        if (fgResult != null) return fgResult

        return null
    }

    private suspend fun getVpnOwnerByDumpsys(): String? {
        // Берём весь дамп в Kotlin и парсим регексами — sh-chain ломался на
        // разных форматах Android (A11 'type: VPN[', A13+ 'Transports: VPN',
        // A14+ иногда '[VPN]'). Единый парсер проще расширять.
        val dump = shell.runShell("dumpsys", "connectivity")
            ?.takeIf { !it.startsWith("ERROR:") }
            ?: return null

        val uid = extractVpnOwnerUid(dump) ?: return null

        // pm list packages --uid <uid> может вернуть несколько пакетов shared-uid,
        // нам нужен ЛЮБОЙ валидный. На старом API иногда одна строка, на новом
        // Android 14 — вывод `package:a  package:b` через пробел.
        val pkgOutput = shell.runShell("pm", "list", "packages", "--uid", uid.toString())
            ?.takeIf { !it.startsWith("ERROR:") }
            ?: return null

        return pkgOutput.lineSequence()
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.length > "package:".length }
            ?.removePrefix("package:")
    }


    private suspend fun getVpnOwnerByForegroundService(): String? {
        for (client in VpnClientType.entries) {
            val output = shell.runShell(
                "sh", "-c",
                "dumpsys activity services ${client.packageName} 2>/dev/null | grep -c 'isForeground=true'"
            )
            if ((output?.trim()?.toIntOrNull() ?: 0) > 0) {
                return client.packageName
            }
        }
        return null
    }

    private fun isVpnCurrentlyActive(cm: ConnectivityManager): Boolean {
        return try {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "VpnClientManager"
    }
}

/**
 * Разные Android версии печатают VPN-agent в dumpsys по-разному. Ищем
 * OwnerUid в блоке NetworkAgentInfo, у которого есть признак VPN:
 *   - "type: VPN["          — до Android 12
 *   - "Transports: VPN"     — Android 12+
 *   - "[VPN]"               — иногда на Android 14/15
 *
 * Возвращаем первый найденный uid > 1000 (приложение, не системный демон).
 *
 * Top-level (не method) чтобы можно было тестировать без инстанса VpnClientManager —
 * функция чистая, state класса не использует.
 */
internal fun extractVpnOwnerUid(dump: String): Int? {
    val ownerUidRx = Regex("""OwnerUid[:=\s]+(\d+)""")
    val vpnMarkerRx = Regex("""(?:type: VPN\[|Transports:[^\n]*\bVPN\b|\[VPN\])""")

    // Разбиваем на абзацы по пустой строке — NetworkAgentInfo обычно занимает один блок.
    for (block in dump.split("\n\n", "\n \n")) {
        if (!vpnMarkerRx.containsMatchIn(block)) continue
        val uid = ownerUidRx.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        if (uid > 1000) return uid
    }

    // Fallback: скользящее окно в 30 строк после маркера (на случай если блоки слеплены).
    val lines = dump.lineSequence().toList()
    for (i in lines.indices) {
        if (!vpnMarkerRx.containsMatchIn(lines[i])) continue
        val window = lines.subList(i, minOf(i + 30, lines.size)).joinToString("\n")
        val uid = ownerUidRx.find(window)?.groupValues?.get(1)?.toIntOrNull() ?: continue
        if (uid > 1000) return uid
    }
    return null
}
