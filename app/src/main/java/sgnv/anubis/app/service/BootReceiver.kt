package sgnv.anubis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("freeze_on_boot", false)) return

        val app = context.applicationContext as AnubisApp
        val shizukuManager = app.shizukuManager
        val repo = app.appRepository

        CoroutineScope(Dispatchers.IO).launch {
            // После ребута Shizuku может подняться не сразу — ждём до 30с разрешение + bind UserService.
            if (!shizukuManager.awaitShizukuReady()) return@launch

            // Freeze LOCAL + VPN_ONLY on boot (no VPN active)
            for (group in listOf(AppGroup.LOCAL, AppGroup.VPN_ONLY)) {
                val packages = repo.getPackagesByGroup(group)
                for (pkg in packages) {
                    if (shizukuManager.isAppInstalled(pkg)) {
                        shizukuManager.freezeApp(pkg)
                    }
                }
            }
        }
    }
}
