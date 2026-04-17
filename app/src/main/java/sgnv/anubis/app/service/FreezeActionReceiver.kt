package sgnv.anubis.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup

/**
 * Обрабатывает action'ы из hit-нотификаций (Ask / Auto режимы из HitNotifier).
 * Каждое действие — add-to-LOCAL или toggle-freeze — асинхронно через
 * ShizukuManager, результат просто удаляет нотификацию.
 */
class FreezeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val action = intent.action ?: return
        val app = context.applicationContext as AnubisApp
        val shizuku = app.shizukuManager
        val repo = app.appRepository

        // Нотификация снимается сразу, операции гоняем на IO scope.
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(pkg.hashCode())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            // Бинд Shizuku может быть ещё не готов если процесс только поднялся.
            if (!shizuku.awaitUserService(timeoutMs = 3_000L)) return@launch

            when (action) {
                ACTION_FREEZE -> {
                    repo.setAppGroup(pkg, AppGroup.LOCAL)
                    if (!shizuku.isAppFrozen(pkg)) shizuku.freezeApp(pkg)
                }
                ACTION_UNFREEZE -> {
                    if (shizuku.isAppFrozen(pkg)) shizuku.unfreezeApp(pkg)
                }
                ACTION_DISMISS -> Unit  // только убрали нотификацию
            }
        }
    }

    companion object {
        private const val ACTION_FREEZE = "sgnv.anubis.app.FREEZE_HIT"
        private const val ACTION_UNFREEZE = "sgnv.anubis.app.UNFREEZE_HIT"
        private const val ACTION_DISMISS = "sgnv.anubis.app.DISMISS_HIT"
        private const val EXTRA_PKG = "pkg"

        fun pendingFreeze(ctx: Context, pkg: String): PendingIntent = build(ctx, ACTION_FREEZE, pkg)
        fun pendingUnfreeze(ctx: Context, pkg: String): PendingIntent = build(ctx, ACTION_UNFREEZE, pkg)
        fun pendingDismiss(ctx: Context, pkg: String): PendingIntent = build(ctx, ACTION_DISMISS, pkg)

        private fun build(ctx: Context, action: String, pkg: String): PendingIntent {
            val intent = Intent(ctx, FreezeActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_PKG, pkg)
            }
            // RequestCode уникален per (action, pkg) чтобы PendingIntent.extras
            // не затирали друг друга при параллельных хитах от разных пакетов.
            val reqCode = (action + pkg).hashCode()
            return PendingIntent.getBroadcast(
                ctx, reqCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
