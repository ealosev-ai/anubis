package sgnv.anubis.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Скачивает self-update APK в cacheDir, сверяет SHA-256 с ожидаемой,
 * возвращает готовый [Intent] для запуска системного установщика
 * (через FileProvider, потому что Android 7+ не любит file://).
 *
 * Логика безопасности: если [UpdateInfo.apkSha256] null — мы даже не
 * начинаем скачивание; пользователь в диалоге увидит warning и пойдёт
 * в браузер (это явный opt-in на unverified).
 */
object UpdateInstaller {

    sealed interface Result {
        data class Ready(val installIntent: Intent) : Result
        data class HashMismatch(val expected: String, val actual: String) : Result
        data class Error(val message: String) : Result
    }

    suspend fun downloadAndPrepare(context: Context, info: UpdateInfo): Result = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: return@withContext Result.Error("нет прямой ссылки на APK")
        val expected = info.apkSha256?.lowercase()
            ?: return@withContext Result.Error("в релизе нет SHA-256 — установка без проверки запрещена")

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Имя файла версией — скачаем один раз и переиспользуем при повторной попытке.
        val apkFile = File(dir, "anubis-${info.latestVersion}.apk")

        try {
            if (!apkFile.exists() || sha256Hex(apkFile) != expected) {
                downloadTo(url, apkFile)
            }
        } catch (e: Exception) {
            return@withContext Result.Error("скачивание упало: ${e.message}")
        }

        val actual = try {
            sha256Hex(apkFile)
        } catch (e: Exception) {
            return@withContext Result.Error("не удалось посчитать SHA-256: ${e.message}")
        }

        if (actual != expected) {
            apkFile.delete()
            return@withContext Result.HashMismatch(expected = expected, actual = actual)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        Result.Ready(install)
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
    }

    internal fun sha256Hex(file: File): String = computeSha256Hex(file)
}

/** Top-level чтобы тестируемость не зависела от UpdateInstaller (object). */
internal fun computeSha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
