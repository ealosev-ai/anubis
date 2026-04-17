package sgnv.anubis.app

import android.content.Context
import sgnv.anubis.app.shizuku.ShellExec
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientManager

/**
 * Тестовый VpnClientManager — вместо реального v2rayNG/NekoBox просто
 * программно переключает `_vpnActive`. Shell-команды не шлёт, NetworkCallback
 * не регистрирует. Всё остальное (`isInstalled`, `getInstalledClients`)
 * работает от реального PackageManager.
 */
class FakeVpnClientManager(
    context: Context,
    shell: ShellExec,
) : VpnClientManager(context, shell) {

    override suspend fun startVPN(client: SelectedVpnClient) {
        _vpnActive.value = true
    }

    override suspend fun stopVPN(client: SelectedVpnClient) {
        _vpnActive.value = false
    }

    override fun launchApp(packageName: String) {
        // В UI-тестах не поднимаем чужие activity.
    }

    override fun startMonitoringVpn() {
        // Намеренно no-op: не регистрируем NetworkCallback в тестах.
    }

    /** Программно симулировать что внешний VPN поднялся / упал. */
    fun setVpnActive(active: Boolean) {
        _vpnActive.value = active
    }
}
