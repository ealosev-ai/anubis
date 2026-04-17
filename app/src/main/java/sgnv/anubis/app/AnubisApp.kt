package sgnv.anubis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sgnv.anubis.app.audit.AuditRepository
import sgnv.anubis.app.audit.HoneypotListener
import sgnv.anubis.app.data.db.AppDatabase
import sgnv.anubis.app.data.repository.AppRepository
import sgnv.anubis.app.shizuku.ShizukuManager

class AnubisApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    lateinit var shizukuManager: ShizukuManager
        private set

    /**
     * Audit-компоненты живут на уровне Application, а не ViewModel — иначе
     * закрытие Activity убивает ServerSocket-ы на localhost и honeypot глохнет.
     * Декой-VPN-нотификация (foreground service) удерживает процесс, поэтому
     * listener реально может молотить сутками. AuditViewModel читает эти же
     * инстансы, так что UI при перезапуске подхватит накопленные suspects.
     */
    val appRepository: AppRepository by lazy { AppRepository(database.managedAppDao(), this) }
    val auditListener: HoneypotListener by lazy { HoneypotListener(shizukuManager) }
    val auditRepository: AuditRepository by lazy { AuditRepository(appRepository) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Init Shizuku once — all components share this instance
        shizukuManager = ShizukuManager(packageManager)
        shizukuManager.startListening()

        // Собираем hits в AuditRepository на application-scope. Эта корутина живёт
        // всё время процесса — SharedFlow с replay=0 ничего не эмитит пока listener
        // не запущен, так что idle-режим бесплатный.
        applicationScope.launch {
            auditListener.hits.collect { hit -> auditRepository.recordHit(hit) }
        }
    }

    override fun onTerminate() {
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
