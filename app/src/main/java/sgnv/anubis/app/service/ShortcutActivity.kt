package sgnv.anubis.app.service

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transparent activity launched from home screen shortcuts.
 * Uses shared ShizukuManager from Application — no wait loop needed.
 */
class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("package") ?: run { finish(); return }
        val groupName = intent.getStringExtra("group") ?: AppGroup.LAUNCH_VPN.name
        val group = try { AppGroup.valueOf(groupName) } catch (e: Exception) { AppGroup.LAUNCH_VPN }

        val app = applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        val vpnClientManager = app.vpnClientManager
        val orchestrator = app.orchestrator

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val pkg = prefs.getString("vpn_client_package", null) ?: VpnClientType.V2RAY_NG.packageName
        val client = SelectedVpnClient.fromPackage(pkg)

        CoroutineScope(Dispatchers.Main).launch {
            // Ждём реальный коннект UserService, а не гадаем через delay().
            if (!shizukuManager.awaitUserService()) {
                finish()
                return@launch
            }

            when (group) {
                AppGroup.LOCAL -> {
                    vpnClientManager.refreshVpnState()
                    vpnClientManager.detectActiveVpnClient()
                    val detectedPkg = vpnClientManager.activeVpnPackage.value
                    val detected = vpnClientManager.activeVpnClient.value
                    val stopClient = if (detected != null) SelectedVpnClient.fromKnown(detected)
                        else detectedPkg?.let { SelectedVpnClient.fromPackage(it) } ?: client
                    orchestrator.launchLocal(packageName, stopClient, detectedPkg)
                }
                AppGroup.VPN_ONLY, AppGroup.LAUNCH_VPN -> {
                    orchestrator.launchWithVpn(packageName, client)
                }
            }

            finish()
        }
    }
}
