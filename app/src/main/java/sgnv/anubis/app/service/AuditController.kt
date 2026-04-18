package sgnv.anubis.app.service

import android.content.Context
import android.content.Intent

/**
 * Единая точка старта/останова фонового аудита.
 *
 * Есть три триггера включения honeypot-listener'а:
 *  - пользователь нажал «Старт» на AuditScreen
 *  - `adb shell am broadcast -a ADB_ENABLE_AUDIT`
 *  - `BootReceiver` при включении устройства (если `audit_background_enabled=true`)
 *
 * Чтобы все три пути вели через один код и одинаково поднимали foreground-notification,
 * они делегируют сюда. Контроллер отвечает за:
 *  - запуск `AuditForegroundService` (специальный FGS, держит процесс в фоне)
 *  - опциональный подъём decoy-VPN (`audit_decoy_with_background`)
 *  - запись флага `audit_background_enabled` в prefs, чтобы `BootReceiver` знал нужен ли auto-start
 */
object AuditController {

    private const val PREFS = "settings"
    const val PREF_BG_ENABLED = "audit_background_enabled"
    const val PREF_DECOY_WITH_BG = "audit_decoy_with_background"
    /** Держим в синке с SettingsController.KEY_DEV_MODE. Статический gate — чтобы BootReceiver тоже уважал. */
    const val PREF_DEV_MODE = "dev_mode_enabled"

    /**
     * Поднять фоновый аудит. Если [persistPreference] = true — записываем `audit_background_enabled`,
     * чтобы BootReceiver перезапустил сервис после ребута. Для временного запуска из AuditScreen
     * «Старт» можно передавать false (listener живёт только пока Anubis в foreground).
     */
    fun start(context: Context, persistPreference: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Dev-mode gate: honeypot-сервис стартует только если экспериментальные
        // функции включены в Settings. Защищает от «залипшего» audit_background_enabled=true
        // из прошлой версии, когда dev_mode ещё не существовал.
        if (!prefs.getBoolean(PREF_DEV_MODE, false)) return
        if (persistPreference) {
            prefs.edit().putBoolean(PREF_BG_ENABLED, true).apply()
        }

        val withDecoy = prefs.getBoolean(PREF_DECOY_WITH_BG, true)
        AuditForegroundService.start(context, withDecoy = withDecoy)
    }

    fun stop(context: Context, persistPreference: Boolean = true) {
        if (persistPreference) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_BG_ENABLED, false).apply()
        }
        AuditForegroundService.stop(context)
        StealthVpnService.stopDecoy(context)
    }

    fun isBackgroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_BG_ENABLED, false)

    fun setDecoyWithBackground(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_DECOY_WITH_BG, enabled).apply()
    }

    fun isDecoyWithBackgroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_DECOY_WITH_BG, true)
}
