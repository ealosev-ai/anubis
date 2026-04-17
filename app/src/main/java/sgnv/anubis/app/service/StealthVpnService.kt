package sgnv.anubis.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.R
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * VPN-сервис в двух режимах.
 *
 * **1. DISCONNECT (старое поведение):** быстрый establish → close чужого VPN-клиента.
 * Android допускает только один VPN одновременно, наш establish() принудительно
 * ревокает чужой. Сразу закрываем свой — в итоге VPN нет ни у кого.
 *
 * **2. DECOY (для аудита, soft-режим):** долгоживущий tun0 БЕЗ default-route —
 * устройство продолжает ходить в интернет через WiFi/Cell нормально, но детекторы
 * видят TRANSPORT_VPN флаг в ConnectivityManager и `tun0` в NetworkInterface.
 * Этого обычно достаточно для триггера канала (4) методички Минцифры — апп
 * проверяет, что его сервер доступен, видит «VPN включён» и идёт сканить
 * 127.0.0.1:1080/9000/... где HoneypotListener их ловит.
 *
 * Почему soft (без blackhole), а не hard: с default-route в blackhole апп
 * теряет связь с сервером и может просто выйти из цикла проверок, не дойдя
 * до scan-фазы. Soft режим оставляет сеть живой → вероятность, что апп
 * дотянется до канала (4), выше.
 *
 * Режим рассчитан на долгий фон (часы/сутки). Auto-stop через [DECOY_TIMEOUT_MS]
 * (24ч) — чтобы забытый tun0 не висел неделями. Системная шторка VPN и
 * нотификация с кнопкой «Выключить» работают в любой момент.
 *
 * Consent: для обоих режимов нужен `prepare()`. Первый раз система покажет диалог.
 */
class StealthVpnService : VpnService() {

    @Volatile
    private var decoyFd: ParcelFileDescriptor? = null
    private var decoyThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        Log.w(TAG, "decoy auto-stop fired after ${DECOY_TIMEOUT_MS / 3_600_000}h — tun0 был забыт")
        stopDecoy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Если сервис запущен через startForegroundService() (из AdbAuditReceiver
        // в фоне), Android требует startForeground() в течение 5 секунд.
        // Показываем нотификацию сразу — для DECOY_START она и так нужна,
        // для DECOY_STOP/DISCONNECT быстро отработаем и уберём.
        if (intent?.action == ACTION_DECOY_START) {
            showDecoyNotification()
        }
        when (intent?.action) {
            ACTION_DISCONNECT -> doDisconnect()
            ACTION_DECOY_START -> startDecoy()
            ACTION_DECOY_STOP -> stopDecoy()
        }
        // DECOY должен жить — для него START_STICKY полезнее, чтобы Android не убил при OOM.
        // Для DISCONNECT режим stopSelf() отработает и так.
        return if (decoyFd != null) START_STICKY else START_NOT_STICKY
    }

    private fun doDisconnect() {
        try {
            val fd = Builder()
                .addAddress("10.255.255.1", 32)
                .setSession("stealth-disconnect")
                .setBlocking(false)
                .establish()

            if (fd != null) {
                // Our VPN established → other VPN is revoked
                fd.close()
                Log.d(TAG, "Dummy VPN established and closed — other VPN disconnected")
            } else {
                Log.w(TAG, "establish() returned null — no VPN consent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish dummy VPN", e)
        }
        stopSelf()
    }

    private fun startDecoy() {
        if (decoyFd != null) {
            Log.d(TAG, "decoy already up, ignoring start")
            return
        }
        try {
            // Soft-приманка: только локальный subnet, БЕЗ default-route и БЕЗ DNS.
            // Интернет устройства продолжает работать через WiFi/Cell (default
            // маршрут остаётся там). Детекторы видят:
            //   - TRANSPORT_VPN в ConnectivityManager
            //   - tun0 в NetworkInterface
            // Этих двух маркеров для большинства детекторов достаточно, чтобы
            // решить «VPN включён» и триггернуть scan 127.0.0.1 — что нам и нужно.
            //
            // ВАЖНО: НЕ зовём .addDnsServer(...) — если указать любой DNS, Android
            // заворачивает ВСЕ DNS-запросы от всех приложений через этот сервер.
            // Фейковый 10.8.0.1 не ответит → DNS ложится → телефон без интернета.
            // Без addDnsServer Android использует DNS underlying-network (WiFi/Cell),
            // интернет продолжает работать. Детекторам DNS не виден, они смотрят
            // только на TRANSPORT_VPN/NetworkInterface — этот сигнал остаётся.
            //
            // Drain-поток всё равно нужен: некоторые приложения/сервисы могут
            // спонтанно стукнуться в 10.8.0.x и пакет упадёт в tun0, надо
            // вычитывать чтобы fd не забился.
            val fd = Builder()
                .addAddress("10.8.0.2", 24)
                // НЕТ .addRoute("0.0.0.0", 0) — это был бы hard-режим с blackhole.
                .addRoute("10.8.0.0", 24)
                .setSession("anubis-decoy")
                .setBlocking(true)
                .establish()
            if (fd == null) {
                Log.w(TAG, "decoy establish() returned null — no VPN consent")
                stopSelf()
                return
            }
            decoyFd = fd
            _decoyActive.value = true
            decoyThread = thread(name = "anubis-decoy-drain", isDaemon = true) {
                val stream = try { FileInputStream(fd.fileDescriptor) } catch (_: Exception) { return@thread }
                val buf = ByteArray(32_768)
                while (decoyFd != null) {
                    try {
                        val n = stream.read(buf)
                        if (n < 0) break
                    } catch (_: Exception) {
                        break
                    }
                }
                Log.d(TAG, "decoy drain thread exited")
            }
            showDecoyNotification()
            mainHandler.postDelayed(autoStopRunnable, DECOY_TIMEOUT_MS)
            Log.e(TAG, "decoy VPN up (tun0, 10.8.0.2/24, soft — internet still works via WiFi/Cell)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start decoy VPN", e)
            stopSelf()
        }
    }

    private fun stopDecoy() {
        mainHandler.removeCallbacks(autoStopRunnable)
        val fd = decoyFd
        decoyFd = null
        _decoyActive.value = false
        try { fd?.close() } catch (_: Exception) {}
        decoyThread = null
        hideDecoyNotification()
        Log.e(TAG, "decoy VPN down")
        stopSelf()
    }

    /** Нотификация с кнопкой быстрого выключения. Без неё пользователь может застрять. */
    private fun showDecoyNotification() {
        val stopPi = PendingIntent.getService(
            this,
            /* requestCode = */ 42,
            Intent(this, StealthVpnService::class.java).setAction(ACTION_DECOY_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, AnubisApp.CHANNEL_ID)
            .setContentTitle("Anubis: приманка VPN (soft)")
            .setContentText("Интернет работает. Honeypot слушает. Auto-stop через ${DECOY_TIMEOUT_MS / 3_600_000} ч.")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(stopPi)
            .addAction(0, "Выключить", stopPi)
            .build()
        try {
            // startForeground() а не notify(): при запуске через
            // startForegroundService() (из AdbAuditReceiver в фоне) Android
            // требует startForeground() в течение 5 сек, иначе ANR.
            startForeground(DECOY_NOTIFICATION_ID, notif)
        } catch (_: Exception) {
            // Fallback на обычный notify если startForeground провалился
            // (например, тип foreground service не объявлен).
            try {
                NotificationManagerCompat.from(this).notify(DECOY_NOTIFICATION_ID, notif)
            } catch (_: SecurityException) {}
        }
    }

    private fun hideDecoyNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(DECOY_NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    override fun onRevoke() {
        // Срабатывает, если система отозвала VPN consent (например, пользователь
        // вручную поднял другой VPN или нажал «Разрешить» для другого VPN-клиента).
        stopDecoy()
        stopSelf()
    }

    override fun onDestroy() {
        // Service погибает — закрываем fd чтобы не оставить утечку в ядре.
        decoyFd?.let { try { it.close() } catch (_: Exception) {} }
        decoyFd = null
        _decoyActive.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StealthVpnService"
        const val ACTION_DISCONNECT = "sgnv.anubis.app.FORCE_DISCONNECT_VPN"
        const val ACTION_DECOY_START = "sgnv.anubis.app.DECOY_VPN_START"
        const val ACTION_DECOY_STOP = "sgnv.anubis.app.DECOY_VPN_STOP"
        private const val DECOY_NOTIFICATION_ID = 2

        /**
         * Реальный флаг «decoy поднят» — процесс-wide. UI биндится сюда через
         * AuditViewModel.decoyActive, так что при recreate Activity состояние
         * не теряется. Пишется из startDecoy()/stopDecoy()/onDestroy() этого
         * же процесса, поэтому JVM-singleton на companion — достаточно.
         */
        private val _decoyActive = MutableStateFlow(false)
        val decoyActive: StateFlow<Boolean> = _decoyActive
        /**
         * Auto-stop через 24 часа. Soft-режим интернет не блокирует, но забытый
         * tun0 — расход батарейки и ненужный артефакт в системе. Пользователь
         * может выключить раньше через кнопку в нотификации / UI / системный VPN-диалог.
         */
        private const val DECOY_TIMEOUT_MS = 24 * 60 * 60_000L

        /**
         * Check if we have VPN consent.
         * Returns null if we have it, or an Intent to show the system dialog.
         */
        fun prepareVpn(context: Context): Intent? = prepare(context)

        /**
         * Start the dummy VPN to disconnect any active VPN.
         * Only works if we have VPN consent (prepareVpn returns null).
         */
        fun disconnect(context: Context) {
            val intent = Intent(context, StealthVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }

        /** Поднять decoy-tun0 для аудит-режима (детекторы увидят VPN). */
        fun startDecoy(context: Context) {
            val intent = Intent(context, StealthVpnService::class.java).apply {
                action = ACTION_DECOY_START
            }
            // startForegroundService() вместо startService(): на Android 12+
            // startService() кидает BackgroundServiceStartNotAllowedException
            // если апп в фоне (например, из BroadcastReceiver). startForeground()
            // вызывается в onStartCommand() через showDecoyNotification().
            context.startForegroundService(intent)
        }

        /** Опустить decoy-tun0. */
        fun stopDecoy(context: Context) {
            val intent = Intent(context, StealthVpnService::class.java).apply {
                action = ACTION_DECOY_STOP
            }
            context.startService(intent)
        }
    }
}
