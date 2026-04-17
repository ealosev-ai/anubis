package sgnv.anubis.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Чтобы фоновый аудит реально жил на Honor/MagicOS, пользователю нужно пройти три
 * бюрократических окна:
 *
 *  1. POST_NOTIFICATIONS — иначе FGS-notification не видна → AOS может убить сервис
 *     (начиная с API 34 обязательно видимое foreground-notification).
 *  2. Исключение из Battery Optimization — иначе Doze mode усыпляет процесс.
 *  3. Honor «Запуск приложений» → Ручное управление — специфичная ещё-одна-настройка
 *     у Huawei/Honor, без неё MagicOS убивает процесс независимо от battery whitelist.
 *
 * Первые две детектируются программно (через `NotificationManager.areNotificationsEnabled()`
 * и `PowerManager.isIgnoringBatteryOptimizations()`). Третью детектировать нельзя —
 * Honor эту настройку не раскрывает через API. Держим флаг в prefs: «user подтвердил
 * что прошёл это окно». Флаг сбрасывается если мы перехватили сервис-kill
 * (gap > 1ч в watchdog) — значит реально что-то не так.
 */
object BatteryOnboarding {

    private const val PREFS = "settings"
    const val PREF_HONOR_STARTUP_CONFIRMED = "honor_startup_confirmed"

    fun isHonorOrHuawei(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return "honor" in m || "huawei" in m
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun isHonorStartupConfirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_HONOR_STARTUP_CONFIRMED, false)

    fun setHonorStartupConfirmed(context: Context, confirmed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HONOR_STARTUP_CONFIRMED, confirmed).apply()
    }

    /**
     * Открыть системный экран настройки оптимизации батареи на наш пакет.
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS требует permission, мы её
     * объявили в manifest. Если пользователь согласится — `isIgnoringBatteryOptimizations`
     * вернёт true на следующем чеке.
     */
    fun requestBatteryWhitelist(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback — обычный экран настройки приложения
            openAppSettings(context)
        }
    }

    fun openAppNotificationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    /**
     * Попытаться открыть Honor «Запуск приложений» (StartupAppControlActivity).
     * Компонент называется по-разному в разных версиях MagicOS — пробуем
     * несколько кандидатов. Если ни один не найден — fallback в общие App Settings
     * (оттуда пользователь сам доберётся до «Батарея → Ручное управление»).
     */
    fun openHonorStartupControl(context: Context): Boolean {
        val candidates = listOf(
            // MagicOS 7+ (PowerGenie)
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            // старые версии
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            // MagicOS 10 — Honor переименовал пакет
            ComponentName(
                "com.hihonor.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        for (cn in candidates) {
            try {
                val intent = Intent()
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Проверяем что система знает эту активити — без этого startActivity
                // упадёт ActivityNotFoundException втихомолку.
                context.packageManager.resolveActivity(intent, 0) ?: continue
                context.startActivity(intent)
                return true
            } catch (_: Exception) { /* try next */ }
        }
        openAppSettings(context)
        return false
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) { /* nothing we can do */ }
    }
}
