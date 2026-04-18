package sgnv.anubis.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Источник списков restricted-приложений: кеш на диске + remote-sync из GitHub.
 *
 * Идея: текущий список RU-приложений для freeze часто растёт («давят на всех,
 * даже методичка есть»), и каждое изменение через APK-перевыпуск — слишком
 * тяжеловесно. Берём из репозитория автора напрямую raw.githubusercontent.com,
 * кешируем в `files/restricted_list_cache.json`, падаем на built-in
 * [DefaultRestrictedApps]/[DefaultVpnOnlyApps] если сеть недоступна.
 *
 * Сетевой запрос идёт к GitHub (через Cloudflare) — этот endpoint не связан
 * с AppMetrica или другими RU-трекерами, VPN-статус сюда не сливается; для
 * threat-model Anubis это OK.
 */
class RestrictedListProvider(private val context: Context) {

    private val cacheFile: File = File(context.filesDir, CACHE_FILE)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cached: RestrictedList = loadInitial()

    /** Текущий эффективный список. Берётся из кеша, fallback — built-in. */
    fun current(): RestrictedList = cached

    fun lastSyncMs(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)
    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    /**
     * Забрать свежий JSON с GitHub и обновить кеш. Возвращает Result с timestamp
     * выпуска списка или ошибкой. Безопасно вызывать параллельно с current() —
     * кеш читается через Volatile-ссылку.
     */
    suspend fun sync(url: String = REMOTE_URL): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Anubis/sync")
            val code = conn.responseCode
            if (code != 200) {
                val msg = "HTTP $code"
                prefs.edit().putString(KEY_LAST_ERROR, msg).apply()
                return@withContext Result.failure(RuntimeException(msg))
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parse(text)  // бросит если невалидный
            cacheFile.writeText(text)
            cached = parsed
            prefs.edit()
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()
            Log.i(TAG, "synced: ${parsed.packageNames.size} restricted + ${parsed.vpnOnlyPackageNames.size} vpnOnly")
            Result.success(parsed.updatedEpochMs)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            prefs.edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
            Result.failure(e)
        }
    }

    private fun loadInitial(): RestrictedList {
        if (cacheFile.exists()) {
            try {
                return parse(cacheFile.readText())
            } catch (e: Exception) {
                Log.w(TAG, "cache parse failed, falling back to built-in: ${e.message}")
            }
        }
        return builtIn()
    }

    private fun builtIn(): RestrictedList = RestrictedList(
        restricted = DefaultRestrictedApps.categories.map {
            RestrictedList.Category(it.id, it.label, it.packages)
        },
        vpnOnly = DefaultVpnOnlyApps.categories.map {
            RestrictedList.Category(it.id, it.label, it.packages)
        },
        prefixPatterns = DefaultRestrictedApps.prefixPatterns,
        neverRestrict = DefaultRestrictedApps.neverRestrict,
        updatedEpochMs = 0L,
    )

    private fun parse(json: String): RestrictedList {
        val root = JSONObject(json)
        val restricted = root.parseCategories("restricted")
        val vpnOnly = root.parseCategories("vpnOnly")
        val prefixes = root.optJSONArray("prefixPatterns")?.toStringList() ?: emptyList()
        val never = root.optJSONArray("neverRestrict")?.toStringList()?.toSet() ?: emptySet()
        val updated = root.optLong("updatedEpochMs", 0L)
        if (restricted.isEmpty() && vpnOnly.isEmpty()) {
            throw RuntimeException("empty categories in JSON")
        }
        return RestrictedList(restricted, vpnOnly, prefixes, never, updated)
    }

    private fun JSONObject.parseCategories(key: String): List<RestrictedList.Category> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = mutableListOf<RestrictedList.Category>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id")
            val label = obj.optString("label", id)
            val pkgs = obj.optJSONArray("packages")?.toStringList()?.toSet() ?: emptySet()
            if (id.isNotEmpty() && pkgs.isNotEmpty()) {
                out += RestrictedList.Category(id, label, pkgs)
            }
        }
        return out
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out += getString(i)
        return out
    }

    companion object {
        private const val TAG = "RestrictedListProvider"
        private const val CACHE_FILE = "restricted_list_cache.json"
        private const val PREFS = "settings"
        const val KEY_LAST_SYNC = "restricted_list_last_sync_ms"
        const val KEY_LAST_ERROR = "restricted_list_last_error"
        /** TTL для авто-sync'а на старте app — 24 часа. */
        const val SYNC_TTL_MS: Long = 24L * 60 * 60 * 1000
        /** Источник списка. Форк автора — ealosev-ai/anubis, ветка main. */
        const val REMOTE_URL = "https://raw.githubusercontent.com/ealosev-ai/anubis/main/default_restricted.json"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}

/**
 * Нормализованный набор. Используется вместо `DefaultRestrictedApps.*` там где
 * нужна дин-подгрузка. Built-in классы остались как fallback и для тестов.
 */
data class RestrictedList(
    val restricted: List<Category>,
    val vpnOnly: List<Category>,
    val prefixPatterns: List<String>,
    val neverRestrict: Set<String>,
    val updatedEpochMs: Long,
) {
    data class Category(val id: String, val label: String, val packages: Set<String>)

    val packageNames: Set<String> = restricted.fold(emptySet()) { acc, c -> acc + c.packages }
    val vpnOnlyPackageNames: Set<String> = vpnOnly.fold(emptySet()) { acc, c -> acc + c.packages }

    fun isKnownRestricted(packageName: String): Boolean {
        if (packageName in neverRestrict) return false
        return packageName in packageNames || prefixPatterns.any { packageName.startsWith(it) }
    }
}
