package sgnv.anubis.app.audit

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.service.FreezeActionReceiver
import sgnv.anubis.app.shizuku.FreezeActions

/**
 * При каждом honeypot-хите с резолвенным pkg шлём нотификацию с actions:
 *   - [AUTO]    «Поймали X, заморожен. Разморозить»  (если user уже включил auto-freeze)
 *   - [ASK]     «Поймали X. Заморозить? | Отклонить» (default mode)
 *   - [OFF]     ничего (hit всё равно попадает в AuditScreen)
 *
 * Режим хранится в SharedPreferences ключом `hit_action_mode` = off|ask|auto.
 * Default — off: ложноположительные + чужие сканеры хуже пропущенных, пусть
 * пользователь сам включит когда освоится.
 *
 * Actions обрабатывает [FreezeActionReceiver] — маленький BroadcastReceiver,
 * который делает setAppGroup + freeze/unfreeze в background-сервисе.
 */
class HitNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hits: SharedFlow<AuditHit>,
    shizuku: FreezeActions,
) {
    enum class Mode { OFF, ASK, AUTO }

    private val resolver = HitActionResolver(
        freeze = shizuku,
        selfPackage = context.packageName,
        modeProvider = ::currentMode,
    )

    fun start() {
        scope.launch {
            hits.collect { hit -> handle(hit) }
        }
    }

    private suspend fun handle(hit: AuditHit) {
        when (val action = resolver.decide(hit)) {
            HitActionResolver.Action.Skip -> Unit
            is HitActionResolver.Action.ShowAsk -> notifyAsk(action.pkg, action.hit)
            is HitActionResolver.Action.ShowAuto -> notifyAuto(action.pkg, action.hit)
            is HitActionResolver.Action.FreezeFailed -> Unit  // лог уже в resolver
        }
    }

    private fun currentMode(): Mode {
        val raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("hit_action_mode", "off")
        return when (raw) {
            "ask" -> Mode.ASK
            "auto" -> Mode.AUTO
            else -> Mode.OFF
        }
    }

    private fun notifyAsk(pkg: String, hit: AuditHit) {
        val label = labelOf(pkg)
        val freezeAction = FreezeActionReceiver.pendingFreeze(context, pkg)
        val dismissAction = FreezeActionReceiver.pendingDismiss(context, pkg)

        val notif: Notification = NotificationCompat.Builder(context, AnubisApp.CHANNEL_ID)
            .setContentTitle("Подозрительный сканер: $label")
            .setContentText("Порт ${hit.port} · добавить в LOCAL и заморозить?")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "Заморозить", freezeAction)
            .addAction(0, "Отклонить", dismissAction)
            .build()
        post(pkg, notif)
    }

    private fun notifyAuto(pkg: String, hit: AuditHit) {
        val label = labelOf(pkg)
        val unfreezeAction = FreezeActionReceiver.pendingUnfreeze(context, pkg)
        val notif: Notification = NotificationCompat.Builder(context, AnubisApp.CHANNEL_ID)
            .setContentTitle("Поймали и заморозили: $label")
            .setContentText("Порт ${hit.port} · авто-заморозка в LOCAL")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "Разморозить", unfreezeAction)
            .build()
        post(pkg, notif)
    }

    private fun labelOf(pkg: String): String = try {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        info.loadLabel(context.packageManager).toString()
    } catch (_: Exception) { pkg }

    private fun post(pkg: String, notif: Notification) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(pkg.hashCode(), notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS не дано — фича просто не видна пользователю
        }
    }

    private companion object { const val TAG = "HitNotifier" }
}
