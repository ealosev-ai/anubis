package sgnv.anubis.app.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sgnv.anubis.app.service.AuditController
import sgnv.anubis.app.service.VpnMonitorService
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager
import sgnv.anubis.app.vpn.VpnClientType

/**
 * Настройки приложения: выбор VPN-клиента, режим заморозки (disable-user / suspend),
 * действие при honeypot-хите (off/ask/auto), фоновый VPN-мониторинг.
 *
 * Всё что меняется через SettingsScreen сидит здесь: prefs-чтение + запись +
 * StateFlow для UI. MainViewModel только проксирует.
 */
class SettingsController(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val vpnClientManager: VpnClientManager,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _selectedVpnClient = MutableStateFlow(loadSelectedClient())
    val selectedVpnClient: StateFlow<SelectedVpnClient> = _selectedVpnClient

    private val _installedVpnClients = MutableStateFlow<List<VpnClientType>>(emptyList())
    val installedVpnClients: StateFlow<List<VpnClientType>> = _installedVpnClients

    private val _backgroundMonitoring = MutableStateFlow(loadBackgroundMonitoring())
    val backgroundMonitoring: StateFlow<Boolean> = _backgroundMonitoring

    private val _hitActionMode = MutableStateFlow(loadHitActionMode())
    val hitActionMode: StateFlow<String> = _hitActionMode

    private val _auditBackground = MutableStateFlow(AuditController.isBackgroundEnabled(context))
    val auditBackground: StateFlow<Boolean> = _auditBackground

    private val _auditDecoyWithBackground = MutableStateFlow(
        AuditController.isDecoyWithBackgroundEnabled(context),
    )
    val auditDecoyWithBackground: StateFlow<Boolean> = _auditDecoyWithBackground

    /**
     * Экспериментальные функции: honeypot-listener, активный probe трафика,
     * decoy-VPN. По умолчанию off — они создают нагрузку (bind 14 портов,
     * shizuku-штормы), на Honor MagicOS могут триггернуть ANR.
     * Включается вручную в Settings для опытных пользователей.
     */
    private val _devMode = MutableStateFlow(prefs.getBoolean(KEY_DEV_MODE, false))
    val devMode: StateFlow<Boolean> = _devMode

    init {
        if (_backgroundMonitoring.value) VpnMonitorService.start(context)
        // AuditController.start в BootReceiver уже поднимет сервис при перезагрузке.
        // Здесь поднимаем при «холодном» старте app — если флаг был включён, но
        // сервис почему-то не жив (swipe recents, сбой Honor battery).
        // Под dev_mode: если пользователь выключил экспериментальные функции,
        // но старый audit_background_enabled остался true — принудительно
        // останавливаем, чтобы не молотил в фоне скрытно от пользователя.
        if (_devMode.value && _auditBackground.value) {
            AuditController.start(context, persistPreference = false)
        } else if (!_devMode.value && _auditBackground.value) {
            AuditController.stop(context, persistPreference = true)
            _auditBackground.value = false
        }
        refreshVpnClients()
    }

    fun setDevMode(enabled: Boolean) {
        _devMode.value = enabled
        prefs.edit().putBoolean(KEY_DEV_MODE, enabled).apply()
        if (!enabled && _auditBackground.value) {
            // выключили dev_mode → фоновый аудит сразу останавливается
            AuditController.stop(context, persistPreference = true)
            _auditBackground.value = false
        }
    }

    private companion object {
        const val KEY_DEV_MODE = "dev_mode_enabled"
    }

    fun refreshVpnClients() {
        _installedVpnClients.value = vpnClientManager.getInstalledClients()
    }

    fun selectVpnClient(client: SelectedVpnClient) {
        _selectedVpnClient.value = client
        prefs.edit().putString("vpn_client_package", client.packageName).apply()
    }

    fun isVpnClientEnabled(packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0).enabled
        } catch (_: Exception) { false }
    }

    fun setBackgroundMonitoring(enabled: Boolean) {
        _backgroundMonitoring.value = enabled
        prefs.edit().putBoolean("background_monitoring", enabled).apply()
        if (enabled) VpnMonitorService.start(context) else VpnMonitorService.stop(context)
    }

    fun setAuditBackground(enabled: Boolean) {
        _auditBackground.value = enabled
        if (enabled) {
            AuditController.start(context, persistPreference = true)
        } else {
            AuditController.stop(context, persistPreference = true)
        }
    }

    fun setAuditDecoyWithBackground(enabled: Boolean) {
        _auditDecoyWithBackground.value = enabled
        AuditController.setDecoyWithBackground(context, enabled)
    }

    fun setHitActionMode(mode: String) {
        val normalized = if (mode in setOf("off", "ask", "auto")) mode else "off"
        _hitActionMode.value = normalized
        prefs.edit().putString("hit_action_mode", normalized).apply()
    }

    private fun loadSelectedClient(): SelectedVpnClient {
        // Миграция: старые версии хранили enum name в ключе vpn_client; новые —
        // packageName в vpn_client_package. Оставить обе ветви важно для
        // пользователей которые обновляются с 0.0.x.
        val pkg = prefs.getString("vpn_client_package", null)
        if (pkg != null) return SelectedVpnClient.fromPackage(pkg)
        val legacy = prefs.getString("vpn_client", null)
        return if (legacy != null) {
            try {
                SelectedVpnClient.fromKnown(VpnClientType.valueOf(legacy))
            } catch (_: Exception) {
                SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG)
            }
        } else {
            SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG)
        }
    }

    private fun loadBackgroundMonitoring(): Boolean =
        prefs.getBoolean("background_monitoring", false)

    private fun loadHitActionMode(): String =
        prefs.getString("hit_action_mode", "off") ?: "off"
}
