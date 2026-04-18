package sgnv.anubis.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sgnv.anubis.app.AnubisApp

/**
 * Debug-only receiver. Дампит текущее состояние приложения в JSON, чтобы
 * можно было снять его через `adb shell run-as` без скриншотов.
 *
 * Использование:
 * ```
 * adb shell am broadcast -a sgnv.anubis.app.debug.DUMP \
 *   -n sgnv.anubis.app.debug/sgnv.anubis.app.debug.DebugDumpReceiver
 * adb shell run-as sgnv.anubis.app.debug cat files/debug-dump.json
 * ```
 *
 * Расположен в `app/src/debug/` — в release-сборку не попадёт. Регистрация
 * в `app/src/debug/AndroidManifest.xml`, exported=false (для не-экспортируемых
 * ресиверов `am broadcast` работает только с `-n <component>` от shell-uid —
 * т.е. только ADB / Shizuku, третьи приложения не дотянутся).
 */
class DebugDumpReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AnubisApp ?: run {
            Log.e(TAG, "не AnubisApp, дамп невозможен")
            return
        }
        val pendingResult = goAsync()
        scope.launch {
            try {
                val json = buildDump(app)
                val outFile = java.io.File(app.filesDir, "debug-dump.json")
                outFile.writeText(json)
                Log.e(TAG, "dumped ${outFile.absolutePath} (${json.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "dump failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun buildDump(app: AnubisApp): String {
        val pm = app.packageManager
        val root = JSONObject()
        root.put("dumpedAtMs", System.currentTimeMillis())

        // Stealth orchestrator state
        root.put("stealthState", app.orchestrator.state.value.name)
        root.put("lastError", app.orchestrator.lastError.value ?: JSONObject.NULL)
        root.put("frozenVersion", app.orchestrator.frozenVersion.value)

        // VPN
        root.put(
            "vpn",
            JSONObject().apply {
                put("active", app.vpnClientManager.vpnActive.value)
                put("activePackage", app.vpnClientManager.activeVpnPackage.value ?: JSONObject.NULL)
                put("activeClient", app.vpnClientManager.activeVpnClient.value?.name ?: JSONObject.NULL)
            },
        )

        // Shizuku
        root.put("shizukuStatus", app.shizukuManager.status.value.name)

        // Managed apps — реальное frozen-state через PM, не только флаг в DB.
        val managed = app.appRepository.getInstalledApps().filter { it.group != null }
        val managedArr = JSONArray()
        for (info in managed) {
            val frozen = app.shizukuManager.isAppFrozen(info.packageName)
            val enabled = try {
                pm.getApplicationInfo(info.packageName, 0).enabled
            } catch (_: Exception) {
                null
            }
            managedArr.put(
                JSONObject().apply {
                    put("pkg", info.packageName)
                    put("label", info.label)
                    put("group", info.group?.name ?: JSONObject.NULL)
                    put("frozen", frozen)
                    put("pmEnabled", enabled ?: JSONObject.NULL)
                },
            )
        }
        root.put("managedApps", managedArr)

        // Honeypot debug + running
        val hp = app.auditListener
        val dbg = hp.debug.value
        root.put(
            "honeypot",
            JSONObject().apply {
                put("running", hp.running.value)
                put("startedAtMs", dbg.startedAtMs ?: JSONObject.NULL)
                put("portsListening", JSONArray(dbg.portsListening.toList().sorted()))
                put("portsFailed", JSONArray(dbg.portsFailed.toList().sorted()))
                put("accepts", dbg.accepts)
                put("resolvedUids", dbg.resolvedUids)
                put("resolvedPkgs", dbg.resolvedPkgs)
                put("lastAcceptMs", dbg.lastAcceptMs ?: JSONObject.NULL)
                put("lastAcceptPort", dbg.lastAcceptPort ?: JSONObject.NULL)
                put("lastError", dbg.lastError ?: JSONObject.NULL)
            },
        )

        // Последние 20 хитов
        val hits = app.auditRepository.hitLog.value.take(20)
        val hitsArr = JSONArray()
        for (h in hits) {
            hitsArr.put(
                JSONObject().apply {
                    put("ts", h.timestampMs)
                    put("port", h.port)
                    put("uid", h.uid ?: JSONObject.NULL)
                    put("pkg", h.packageName ?: JSONObject.NULL)
                    put("protocol", h.protocol)
                    put("sni", h.sni ?: JSONObject.NULL)
                    put("preview", h.handshakePreview ?: JSONObject.NULL)
                },
            )
        }
        root.put("recentHits", hitsArr)

        return root.toString(2)
    }

    companion object {
        private const val TAG = "DebugDump"
    }
}
