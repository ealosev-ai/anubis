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
        val action = intent?.action
        // После self-update (ACTION_MY_PACKAGE_REPLACED) восстанавливаем ровно
        // фоновый аудит — freeze-on-boot не нужен, потому что устройство не
        // перезагружалось, заморозки никуда не делись.
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED
        val isReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isReplaced) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        // 1) Фоновый аудит — поднимаем сразу, без ожидания Shizuku (listener
        // не зависит от него). FGS из BOOT_COMPLETED разрешён без специального
        // exemption в Android 12+.
        if (prefs.getBoolean(AuditController.PREF_BG_ENABLED, false)) {
            AuditController.start(context, persistPreference = false)
        }

        // 2) Freeze-on-boot (только настоящий ребут — при self-update заморозка жива)
        if (isBoot && prefs.getBoolean("freeze_on_boot", false)) {
            val app = context.applicationContext as AnubisApp
            val shizukuManager = app.shizukuManager
            val repo = app.appRepository

            CoroutineScope(Dispatchers.IO).launch {
                if (!shizukuManager.awaitShizukuReady()) return@launch

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
}
