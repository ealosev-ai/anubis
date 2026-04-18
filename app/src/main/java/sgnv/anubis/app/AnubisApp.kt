package sgnv.anubis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sgnv.anubis.app.audit.AndroidNativeUidResolver
import sgnv.anubis.app.audit.AndroidPackageResolver
import sgnv.anubis.app.audit.AuditRepository
import sgnv.anubis.app.audit.HitNotifier
import sgnv.anubis.app.audit.HoneypotListener
import sgnv.anubis.app.data.db.AppDatabase
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.service.StealthOrchestrator
import sgnv.anubis.app.shizuku.ShizukuManager
import sgnv.anubis.app.vpn.VpnClientManager

open class AnubisApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    lateinit var shizukuManager: ShizukuManager
        private set

    /**
     * Factory для VpnClientManager — testAnubisApp override возвращает FakeVpnClientManager,
     * чтобы Compose UI-тесты могли программно переключать `vpnActive` без реального v2rayNG.
     * `val vpnClientManager by lazy { createVpnClientManager() }` — первый обращение создаёт
     * инстанс через этот hook.
     */
    protected open fun createVpnClientManager(): VpnClientManager =
        VpnClientManager(this, shizukuManager)

    /**
     * VpnClientManager + Orchestrator — тоже process-singleton. Раньше каждый
     * entry point (MainViewModel / Tile / Shortcut) создавал свои, что приводило
     * к двойным NetworkCallback и гонкам при freeze/unfreeze. Теперь всё через
     * один инстанс, мониторинг VPN поднимается один раз в onCreate.
     */
    val appRepository: AppRepository by lazy { AppRepository(database.managedAppDao(), this) }
    val vpnClientManager: VpnClientManager by lazy { createVpnClientManager() }
    val orchestrator: StealthOrchestrator by lazy {
        StealthOrchestrator(this, shizukuManager, vpnClientManager, appRepository)
    }

    /**
     * Audit-компоненты живут на уровне Application, а не ViewModel — иначе
     * закрытие Activity убивает ServerSocket-ы на localhost и honeypot глохнет.
     * Декой-VPN-нотификация (foreground service) удерживает процесс, поэтому
     * listener реально может молотить сутками. AuditViewModel читает эти же
     * инстансы, так что UI при перезапуске подхватит накопленные suspects.
     */
    val auditListener: HoneypotListener by lazy {
        // Native резолверы работают без Shizuku — быстрее и доступны всегда.
        // ShizukuManager остаётся fallback'ом через /proc/net/tcp для случаев
        // когда Android API вернул null (shared-uid, edge-case).
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        HoneypotListener(
            shell = shizukuManager,
            native = AndroidNativeUidResolver(cm),
            packages = AndroidPackageResolver(packageManager),
        )
    }
    val auditRepository: AuditRepository by lazy {
        AuditRepository(appRepository, database.auditHitDao(), applicationScope)
    }

    protected val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Init Shizuku once — all components share this instance.
        // applicationId передаём явно: ShizukuManager живёт в :core-shizuku и
        // больше не может брать его из BuildConfig хост-приложения.
        shizukuManager = ShizukuManager(packageManager, BuildConfig.APPLICATION_ID)
        shizukuManager.startListening()

        startRuntimeMonitoring()
        checkAuditWatchdog(getSharedPreferences("settings", MODE_PRIVATE))
    }

    /**
     * Если пользователь включал «Фоновый аудит» и последний heartbeat сервиса
     * был больше часа назад — значит Honor/MagicOS убил сервис, а app только
     * теперь восстал. Показываем reminder-нотификацию (поверх обычной системной)
     * чтобы пользователь понял что 24-часовой аудит прерван.
     */
    private fun checkAuditWatchdog(prefs: android.content.SharedPreferences) {
        val bgEnabled = prefs.getBoolean(
            sgnv.anubis.app.service.AuditController.PREF_BG_ENABLED, false,
        )
        if (!bgEnabled) return
        val lastAlive = prefs.getLong(
            sgnv.anubis.app.service.AuditForegroundService.PREF_LAST_ALIVE_MS, 0L,
        )
        if (lastAlive == 0L) return  // ни разу не стартовал — ещё рано ругаться
        val gapMs = System.currentTimeMillis() - lastAlive
        // > 60 минут без heartbeat — сервис был убит в фоне.
        if (gapMs > 60 * 60_000L) {
            showAuditKilledReminder(gapMs)
        }
    }

    private fun showAuditKilledReminder(gapMs: Long) {
        val hours = gapMs / 3_600_000L
        val openIntent = android.app.PendingIntent.getActivity(
            this, 42,
            android.content.Intent(this, sgnv.anubis.app.ui.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Аудит ловушек был прерван")
            .setContentText(
                "Сервис простаивал $hours ч — Honor battery saver убил его. " +
                    "Проверьте «Запуск приложений → Anubis»."
            )
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(99, notif)
        } catch (_: SecurityException) { /* нет POST_NOTIFICATIONS — игнор */ }
    }

    /**
     * Стартуем runtime-компоненты (NetworkCallback, hit-коллектор, HitNotifier).
     * Выделено в open-метод чтобы TestAnubisApp мог override'нуть и не тянуть
     * реальный ConnectivityManager/Shizuku в UI-сценариях.
     */
    protected open fun startRuntimeMonitoring() {
        // Стартуем VPN-мониторинг единожды на процесс. Раньше это делал каждый
        // ViewModel/Tile/Shortcut со своим экземпляром и регистрировал новый
        // NetworkCallback — теперь один.
        vpnClientManager.startMonitoringVpn()

        // Собираем hits в AuditRepository на application-scope. Эта корутина живёт
        // всё время процесса — SharedFlow с replay=0 ничего не эмитит пока listener
        // не запущен, так что idle-режим бесплатный.
        applicationScope.launch {
            auditListener.hits.collect { hit -> auditRepository.recordHit(hit) }
        }

        // Нотификации по хитам: в режиме ASK — спрашиваем «Заморозить? | Отклонить»;
        // в AUTO — фризим сразу и показываем «Разморозить». OFF — ничего не шлём
        // (hits всё равно копятся в AuditRepository для UI).
        HitNotifier(
            context = this,
            scope = applicationScope,
            hits = auditListener.hits,
            shizuku = shizukuManager,
        ).start()
    }

    override fun onTerminate() {
        vpnClientManager.shutdown()
        shizukuManager.stopListening()
        shizukuManager.unbindUserService()
        super.onTerminate()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Stealth Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Мониторинг состояния VPN и замороженных приложений"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "anubis_monitor"
    }
}
