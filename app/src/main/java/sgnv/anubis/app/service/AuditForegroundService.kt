package sgnv.anubis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import sgnv.anubis.app.ui.MainActivity
import java.util.Calendar

/**
 * Держит honeypot-listener в фоне как foreground-service.
 *
 * Почему просто `listener.start()` без сервиса недостаточно: Android убивает
 * backgrounded-процесс Anubis через 10-30 минут (особенно на Honor MagicOS),
 * листенер теряет сокеты, аудит молча умирает. FGS с ongoing notification —
 * сигнал системе «держи процесс живым».
 *
 * foregroundServiceType="specialUse" (manifest) — единственный подходящий тип
 * для кастомной задачи вне списка Android. В manifest property-тегом
 * `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` декларируем назначение
 * `honeypot_audit_listener` — для Play Store потребуется обоснование, но для
 * sideload/F-Droid достаточно.
 *
 * Decoy-VPN поднимается параллельно (`startDecoy=true`) как отдельный VpnService —
 * он и так foreground, мы его не контролируем, только триггерим старт/стоп.
 */
class AuditForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val withDecoy = intent.getBooleanExtra(EXTRA_WITH_DECOY, false)
                startForeground(NOTIFICATION_ID, buildNotification(hitsToday = null))
                startAudit(withDecoy)
                _running.value = true
                startNotificationUpdates()
            }
            ACTION_STOP -> {
                stopAudit()
                _running.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAudit(withDecoy: Boolean) {
        val app = applicationContext as AnubisApp
        app.auditListener.start()
        if (withDecoy) {
            StealthVpnService.startDecoy(this)
        }
    }

    private fun stopAudit() {
        val app = applicationContext as AnubisApp
        app.auditListener.stop()
        // Decoy НЕ останавливаем автоматически — пользователь мог его поднять
        // отдельно, не хотим порвать его. Останов decoy — через AuditController.stop().
    }

    /**
     * Раз в 30с перезаписываем notification с актуальным «хитов сегодня». Нельзя
     * подписаться на Flow напрямую — каждый collect-эмит пересобирает notification,
     * это дорого. Простой ticker с чтением кэшированного suspect-counter'а достаточно.
     */
    private fun startNotificationUpdates() {
        updateJob?.cancel()
        val app = applicationContext as AnubisApp
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        updateJob = scope.launch {
            while (true) {
                // Watchdog heartbeat — AnubisApp.onCreate увидит этот timestamp
                // и поймёт жив ли был сервис недавно. Gap > 1ч = был убит.
                prefs.edit().putLong(PREF_LAST_ALIVE_MS, System.currentTimeMillis()).apply()
                val since = startOfTodayMs()
                val count = try {
                    app.auditRepository.countSinceFlow(since).let { flow ->
                        // Возьмём первое значение (cancellable). Если хотим real-time
                        // в notification — нужно collect, но тогда много rebuild'ов.
                        var snapshot = 0
                        val collector = scope.launch {
                            flow.collect { snapshot = it }
                        }
                        delay(200)
                        collector.cancel()
                        snapshot
                    }
                } catch (_: Exception) {
                    0
                }
                val n = buildNotification(hitsToday = count)
                try {
                    androidx.core.app.NotificationManagerCompat.from(this@AuditForegroundService)
                        .notify(NOTIFICATION_ID, n)
                } catch (_: SecurityException) {
                    // POST_NOTIFICATIONS может быть отозвано — тихо игнорим
                }
                delay(30_000)
            }
        }
    }

    private fun buildNotification(hitsToday: Int?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("open_screen", "audit")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AuditForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when (hitsToday) {
            null -> "Ловушки запущены"
            0 -> "Ловушки активны, за сегодня — тишина"
            else -> "Ловушки активны · за сегодня хитов: $hitsToday"
        }

        return NotificationCompat.Builder(this, AnubisApp.CHANNEL_ID)
            .setContentTitle("Anubis: фоновый аудит")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Остановить", stopIntent)
            .build()
    }

    override fun onDestroy() {
        updateJob?.cancel()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "sgnv.anubis.app.START_AUDIT_FGS"
        const val ACTION_STOP = "sgnv.anubis.app.STOP_AUDIT_FGS"
        const val EXTRA_WITH_DECOY = "with_decoy"
        const val NOTIFICATION_ID = 3
        /** Watchdog heartbeat — timestamp последнего tick'а сервиса. */
        const val PREF_LAST_ALIVE_MS = "audit_service_last_alive_ms"

        private val _running = MutableStateFlow(false)
        /** true пока живёт AuditForegroundService. Для HomeScreen-карточки. */
        val running: StateFlow<Boolean> = _running

        fun start(context: Context, withDecoy: Boolean) {
            val intent = Intent(context, AuditForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WITH_DECOY, withDecoy)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AuditForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun startOfTodayMs(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
