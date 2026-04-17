package sgnv.anubis.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StealthTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val app = application as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = app.vpnClientManager
        val orchestrator = app.orchestrator

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val pkg = prefs.getString("vpn_client_package", null) ?: VpnClientType.V2RAY_NG.packageName
        val client = SelectedVpnClient.fromPackage(pkg)

        scope.launch {
            if (!shizukuManager.awaitUserService()) {
                updateTile()
                return@launch
            }

            val vpnActive = isVpnActive()

            if (!vpnActive) {
                orchestrator.enable(client)
                VpnMonitorService.start(this@StealthTileService)
            } else {
                vpnClientManager.refreshVpnState()
                vpnClientManager.detectActiveVpnClient()
                val detectedPkg = vpnClientManager.activeVpnPackage.value
                orchestrator.disable(client, detectedPkg)
                if (!isVpnActive()) {
                    VpnMonitorService.stop(this@StealthTileService)
                }
            }

            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val vpnActive = isVpnActive()
        tile.state = if (vpnActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (vpnActive) "Stealth ON" else "Stealth OFF"
        tile.updateTile()
    }

    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) { false }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
