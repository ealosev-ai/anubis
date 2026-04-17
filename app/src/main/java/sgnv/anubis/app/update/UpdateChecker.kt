package sgnv.anubis.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sgnv.anubis.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверяет наличие новой версии через GitHub Releases API.
 *
 * API: https://api.github.com/repos/<owner>/<repo>/releases/latest
 * Лимит без токена: 60 req/hour/IP. Для нашего масштаба достаточно.
 */
object UpdateChecker {

    /**
     * Источник релизов хранится в prefs: `fork` (default, свой форк),
     * `upstream` (автор оригинала), `off` (не проверять ничего).
     * Форк сильно расходится с оригиналом — автоматически тянуть обновления
     * автора на нашу сборку нельзя (снесёт нашу логику).
     */
    private const val FORK_REPO = "ealosev-ai/anubis"
    private const val UPSTREAM_REPO = "sogonov/anubis"

    private const val PREFS = "settings"
    private const val KEY_SOURCE = "update_source"
    private const val KEY_ENABLED = "update_check_enabled"
    private const val KEY_LAST_CHECK_MS = "update_last_check_ms"
    private const val KEY_SKIPPED_VERSION = "update_skipped_version"

    private fun apiUrl(context: Context): String? {
        val source = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, "fork") ?: "fork"
        val repo = when (source) {
            "upstream" -> UPSTREAM_REPO
            "fork" -> FORK_REPO
            else -> return null  // off
        }
        return "https://api.github.com/repos/$repo/releases/latest"
    }

    fun getSource(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, "fork") ?: "fork"

    fun setSource(context: Context, source: String) {
        val normalized = if (source in setOf("fork", "upstream", "off")) source else "fork"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SOURCE, normalized).apply()
    }

    private const val MIN_INTERVAL_MS = 60 * 60 * 1000L  // 1 час

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIPPED_VERSION, version).apply()
    }

    private fun skippedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED_VERSION, null)

    /**
     * Проверить обновления.
     * @param force если true — игнорирует кэш (для ручной проверки из настроек).
     * @return UpdateInfo при успехе, null при ошибке или если кэш свежий и force=false.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiUrl = apiUrl(context) ?: return@withContext null  // source = off
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)

        if (!force && now - lastCheck < MIN_INTERVAL_MS) {
            return@withContext null
        }

        try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Anubis/${BuildConfig.VERSION_NAME}")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val info = parseReleaseJson(body, BuildConfig.VERSION_NAME) ?: return@withContext null
            prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
            info
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Чистая функция: GitHub release JSON → [UpdateInfo] или null если не парсится.
     * Вынесена для unit-тестирования (без HTTP/Context).
     *
     * Правила:
     *  - `tag_name` с ведущим `v` → `latestVersion` без `v`.
     *  - В assets ищем первый `.apk` без `debug` в имени — это наш release APK.
     *    Нет подходящего ассета → `apkUrl = null` (пользователь откроет release-страницу).
     *  - В `body` ищем строку `SHA-256: <64 hex>` (регистронезависимо, тире опционален)
     *    → `apkSha256` в lowercase. Это даёт верификацию целостности APK при установке.
     *  - Notes обрезаются до 2000 символов чтобы диалог не распирало.
     */
    internal fun parseReleaseJson(body: String, currentVersion: String): UpdateInfo? {
        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val tagName = json.optString("tag_name").takeIf { it.isNotBlank() }?.trimStart('v')
            ?: return null
        val htmlUrl = json.optString("html_url")
        val rawNotes = json.optString("body")
        val notes = rawNotes.take(2000)
        val sha256 = Regex("""SHA-?256[:\s]+([A-Fa-f0-9]{64})""", RegexOption.IGNORE_CASE)
            .find(rawNotes)?.groupValues?.get(1)?.lowercase()
        val apkUrl = json.optJSONArray("assets")?.let { assets ->
            var found: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true) &&
                    !name.contains("debug", ignoreCase = true)
                ) {
                    found = a.optString("browser_download_url")
                    break
                }
            }
            found
        }
        return UpdateInfo(
            latestVersion = tagName,
            currentVersion = currentVersion,
            releaseUrl = htmlUrl,
            apkUrl = apkUrl,
            releaseNotes = notes,
            apkSha256 = sha256,
        )
    }

    /**
     * True, если обновление доступно и пользователь не попросил его пропустить.
     */
    fun shouldNotify(context: Context, info: UpdateInfo): Boolean {
        if (!info.isUpdateAvailable) return false
        val skipped = skippedVersion(context) ?: return true
        return UpdateInfo.compareVersions(info.latestVersion, skipped) > 0
    }
}
