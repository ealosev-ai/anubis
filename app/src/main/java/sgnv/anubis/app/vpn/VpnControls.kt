package sgnv.anubis.app.vpn

import kotlinx.coroutines.flow.StateFlow

/**
 * Узкий контракт VPN-стороны который нужен StealthOrchestrator-у.
 * VpnClientManager реализует всё это. В тестах подсовываются fake'и чтобы
 * не поднимать ConnectivityManager/NetworkCallback.
 */
interface VpnControls {
    val vpnActive: StateFlow<Boolean>
    suspend fun startVPN(client: SelectedVpnClient)
    suspend fun stopVPN(client: SelectedVpnClient)
    fun launchApp(packageName: String)
    fun refreshVpnState()
}
