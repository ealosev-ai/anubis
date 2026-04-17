package sgnv.anubis.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * ADB-управление аудит-режимом без тапов в UI.
 *
 * Зачем: поднять decoy-VPN + honeypot-listener сейчас можно только через UI
 * (две кнопки на AuditScreen). При отладке это прерывает поток — приходится
 * брать телефон в руки. Этот ресивер решает проблему: одна команда с хоста
 * поднимает/опускает весь аудит.
 *
 * Использование:
 * ```
 * adb shell am broadcast -a sgnv.anubis.app.ADB_ENABLE_AUDIT
 * adb shell am broadcast -a sgnv.anubis.app.ADB_DISABLE_AUDIT
 * ```
 *
 * Безопасность: в манифесте ресивер защищён `android:permission="android.permission.DUMP"`
 * — эту системную permission держит только shell-uid (и ещё пара системных
 * сервисов). Чужие приложения без root этот broadcast послать не смогут.
 *
 * Ограничение: VPN-consent должен быть уже получен (один раз — через UI или
 * предыдущий запуск). Если consent не дан, decoy просто не поднимется,
 * ресивер не взрывается.
 */
class AdbAuditReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ENABLE -> {
                Log.e(TAG, "ENABLE_AUDIT via ADB broadcast")
                requestBatteryWhitelist(context)
                AuditController.start(context, persistPreference = true)
            }
            ACTION_DISABLE -> {
                Log.e(TAG, "DISABLE_AUDIT via ADB broadcast")
                AuditController.stop(context, persistPreference = true)
            }
            else -> Log.w(TAG, "unknown action: ${intent.action}")
        }
    }

    /**
     * Запрашиваем исключение из battery optimization. Без этого Honor MagicOS
     * убивает фоновый процесс ночью, и decoy+audit глохнут. Первый раз
     * покажется системный диалог «Разрешить работу в фоне» — после
     * подтверждения запрос больше не появляется.
     *
     * Дополнительно для Honor: Настройки → Батарея → Запуск приложений →
     * Anubis Debug → «Управление вручную» → все тумблеры ON.
     */
    private fun requestBatteryWhitelist(context: Context) {
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.e(TAG, "requested battery optimization exemption")
            }
        } catch (e: Exception) {
            Log.w(TAG, "battery whitelist request failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AdbAuditReceiver"
        const val ACTION_ENABLE = "sgnv.anubis.app.ADB_ENABLE_AUDIT"
        const val ACTION_DISABLE = "sgnv.anubis.app.ADB_DISABLE_AUDIT"
    }
}
