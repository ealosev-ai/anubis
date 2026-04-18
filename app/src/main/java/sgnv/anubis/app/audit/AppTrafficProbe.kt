package sgnv.anubis.app.audit

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sgnv.anubis.app.shizuku.ShellExec

/**
 * Активный срез трафика одного приложения. Идея такая: пассивный honeypot
 * молчит сутками — детекторы срабатывают *в момент запуска* приложения. Значит
 * ловим в эту секунду: через Shizuku `am start` поднимаем пакет, N секунд пасём
 * `/proc/net/tcp[6]` + `/proc/net/udp[6]`, фильтруем строки по целевому UID,
 * копим уникальные (proto, remote_ip, remote_port) — это и есть «что Сбер делал
 * в первые 15 секунд».
 *
 * Ограничения (чтобы не обольщаться):
 *  - handshake < 1с (TCP SYN+ACK+CLOSE до нашего снапшота) — промахиваем. TCP в
 *    TIME_WAIT висит 60с, поэтому коннекты длиннее ~1с ловятся, но сверхкороткие
 *    UDP-датаграммы уходят молча.
 *  - Пассивные детекторы («спросил ConnectivityManager и решил что VPN есть»)
 *    не оставляют сетевых следов — для них нужен hook-фреймворк, не наш случай.
 *  - WorkManager отложит скан на час после запуска — в 15-секундное окно не
 *    попадёт. Длинное окно помогло бы, но батарею жрёт.
 */
class AppTrafficProbe(
    private val shell: ShellExec,
) {

    data class Endpoint(
        val protocol: String,      // "TCP" | "UDP"
        val localPort: Int,
        val remoteIp: String,      // dotted-v4 или [v6]
        val remotePort: Int,
        val state: String,         // "EST" | "TIME_WAIT" | ...
        val firstSeenElapsedSec: Int,
    )

    /**
     * Запускает пакет и пасёт [durationSec] секунд. Возвращает уникальные
     * endpoint'ы. [onTick] вызывается каждую секунду с elapsed и snapshot size
     * для прогресса UI.
     */
    suspend fun run(
        context: Context,
        packageName: String,
        durationSec: Int = 15,
        onTick: (elapsedSec: Int, foundSoFar: Int) -> Unit = { _, _ -> },
    ): Result<List<Endpoint>> = withContext(Dispatchers.IO) {
        val uid = try {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext Result.failure(IllegalArgumentException("пакет $packageName не установлен"))
        }

        // force-stop чтобы точно запустить с нуля и словить «холодный старт» —
        // именно на этом этапе банки делают пробы (proxy-check, RTT-baseline и пр.).
        shell.execCommand("am", "force-stop", packageName)
        // Небольшая задержка, чтобы kernel очистил TIME_WAIT от прошлой сессии —
        // иначе мы их увидим и засчитаем как "новые" коннекты после старта.
        delay(300L)

        val launch = launchPackage(context, packageName)
        if (launch.isFailure) {
            return@withContext Result.failure(launch.exceptionOrNull() ?: RuntimeException("am start не сработал"))
        }

        val seen = linkedMapOf<String, Endpoint>()
        for (sec in 1..durationSec) {
            delay(1000L)
            val rows = snapshot(uid, sec)
            for (ep in rows) {
                val key = "${ep.protocol}|${ep.remoteIp}|${ep.remotePort}"
                if (key !in seen) seen[key] = ep
            }
            onTick(sec, seen.size)
        }

        Result.success(seen.values.toList())
    }

    private suspend fun launchPackage(context: Context, packageName: String): Result<Unit> {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        val cmp = intent?.component
        return if (cmp != null) {
            shell.execCommand("am", "start", "-n", "${cmp.packageName}/${cmp.className}")
        } else {
            // Fallback: monkey сам найдёт launcher-категорию. Не идеал — он
            // шлёт один "клик", но нам важно чтобы Application.onCreate запустился.
            shell.execCommand("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
        }
    }

    private suspend fun snapshot(targetUid: Int, elapsedSec: Int): List<Endpoint> {
        val rows = mutableListOf<Endpoint>()
        val sources = listOf(
            Triple("/proc/net/tcp", "TCP", false),
            Triple("/proc/net/tcp6", "TCP", true),
            Triple("/proc/net/udp", "UDP", false),
            Triple("/proc/net/udp6", "UDP", true),
        )
        for ((path, proto, isV6) in sources) {
            val content = shell.runShell("cat", path) ?: continue
            if (content.startsWith("ERROR:")) continue
            for (raw in content.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("sl ")) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 8) continue
                val uid = parts[7].toIntOrNull() ?: continue
                if (uid != targetUid) continue
                val (localIp, localPort) = parseAddr(parts[1], isV6) ?: continue
                val (remoteIp, remotePort) = parseAddr(parts[2], isV6) ?: continue
                // Отсекаем listener'ы: серверный сокет у приложения (например
                // Firebase keeps UDP :0 bind на всякий) для нас шум. Интересен
                // именно исходящий коннект с remote ≠ 0.
                if (remotePort == 0) continue
                if (remoteIp == "0.0.0.0" || remoteIp == "::") continue
                // local тоже используем — показать какой локальный порт использовало
                // приложение (редко информативно, но иногда видно что оно открыло).
                rows += Endpoint(
                    protocol = proto,
                    localPort = localPort,
                    remoteIp = remoteIp,
                    remotePort = remotePort,
                    state = decodeState(proto, parts[3]),
                    firstSeenElapsedSec = elapsedSec,
                )
            }
        }
        return rows
    }

    private fun parseAddr(hex: String, isV6: Boolean): Pair<String, Int>? {
        val colon = hex.indexOf(':')
        if (colon <= 0) return null
        val addrHex = hex.substring(0, colon)
        val portHex = hex.substring(colon + 1)
        val port = portHex.toIntOrNull(16) ?: return null
        val ip = if (isV6) parseIpv6(addrHex) else parseIpv4(addrHex)
        return if (ip != null) ip to port else null
    }

    private fun parseIpv4(hex: String): String? {
        if (hex.length != 8) return null
        return try {
            // /proc/net/tcp печатает IPv4 как 32-бит в host-byte-order (LE на ARM/x86).
            // 0100007F → 127.0.0.1 (последний байт "7F" = 127, первый = 1).
            val b4 = hex.substring(0, 2).toInt(16)
            val b3 = hex.substring(2, 4).toInt(16)
            val b2 = hex.substring(4, 6).toInt(16)
            val b1 = hex.substring(6, 8).toInt(16)
            "$b1.$b2.$b3.$b4"
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun parseIpv6(hex: String): String? {
        if (hex.length != 32) return null
        // Kernel печатает 4 слова uint32 в LE. Чтобы получить настоящий BE-порядок
        // байт (как в текстовой форме адреса), переворачиваем каждый 8-символьный
        // блок по-парам.
        val be = buildString(32) {
            for (i in 0 until 4) {
                val w = hex.substring(i * 8, i * 8 + 8)
                append(w.substring(6, 8))
                append(w.substring(4, 6))
                append(w.substring(2, 4))
                append(w.substring(0, 2))
            }
        }.lowercase()
        // IPv4-mapped: ::ffff:X.X.X.X — первые 10 байт 0, байты 10-11 = ff.
        if (be.startsWith("00000000000000000000ffff")) {
            val v4 = be.substring(24, 32)
            return try {
                val b1 = v4.substring(0, 2).toInt(16)
                val b2 = v4.substring(2, 4).toInt(16)
                val b3 = v4.substring(4, 6).toInt(16)
                val b4 = v4.substring(6, 8).toInt(16)
                "::ffff:$b1.$b2.$b3.$b4"
            } catch (_: NumberFormatException) {
                null
            }
        }
        // ::1 — удобно узнаваемо.
        if (be == "00000000000000000000000000000001") return "::1"
        // Остальное — просто 8 групп по 4 hex, без compact-формы. Для UI ОК.
        val groups = (0 until 8).map { i -> be.substring(i * 4, i * 4 + 4).trimStart('0').ifEmpty { "0" } }
        return groups.joinToString(":")
    }

    private fun decodeState(proto: String, hex: String): String {
        if (proto == "UDP") return "UDP"
        return when (hex.uppercase()) {
            "01" -> "EST"
            "02" -> "SYN_SENT"
            "03" -> "SYN_RECV"
            "04" -> "FIN_W1"
            "05" -> "FIN_W2"
            "06" -> "TIME_WAIT"
            "07" -> "CLOSE"
            "08" -> "CLOSE_WAIT"
            "09" -> "LAST_ACK"
            "0A" -> "LISTEN"
            "0B" -> "CLOSING"
            else -> hex
        }
    }

    companion object {
        private const val TAG = "AppTrafficProbe"
    }
}
