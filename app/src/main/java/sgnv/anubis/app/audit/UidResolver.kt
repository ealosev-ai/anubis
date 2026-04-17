package sgnv.anubis.app.audit

import android.os.Process
import android.util.Log
import sgnv.anubis.app.shizuku.ShizukuManager

private const val TAG = "AuditResolver"

/**
 * Резолвер `(srcPort) -> (uid, packageName)` для TCP-подключений к нашему honeypot.
 *
 * Алгоритм:
 * 1. После `accept()` мы знаем remotePort клиента (его исходящий порт, локальный для приложения-клиента).
 * 2. Читаем `/proc/net/tcp` и `/proc/net/tcp6` через Shizuku-shell — там в колонке `uid` видно, кто
 *    установил это соединение. Без Shizuku `/proc/net/tcp` под Android 10+ отдаёт все uid=0
 *    (sandbox), поэтому shell-доступ обязателен.
 * 3. `pm list packages --uid <uid>` через Shizuku → имя пакета.
 *
 * Важно: кэшируем `uid -> pkg`, потому что `pm list` — тяжёлый вызов.
 */
class UidResolver(private val shizukuManager: ShizukuManager) {

    private data class CacheEntry(val pkg: String?, val writtenAtMs: Long)

    // LRU-кэш. `pm list packages` стоит ~50ms, но и кэш не должен расти бесконечно:
    // при долгом аудите на сутках приходят сотни уникальных uid, большинство — шум.
    // removeEldestEntry выкидывает тех, к кому давно не обращались. TTL 1ч — на
    // случай если приложению сменился uid (переустановка с другим user slot).
    private val uidToPackageCache = object : LinkedHashMap<Int, CacheEntry>(
        /* initialCapacity = */ 64,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CacheEntry>?): Boolean =
            size > CACHE_MAX_SIZE
    }

    private fun cacheGet(uid: Int): String? {
        val entry = synchronized(uidToPackageCache) { uidToPackageCache[uid] } ?: return null
        if (System.currentTimeMillis() - entry.writtenAtMs > CACHE_TTL_MS) {
            synchronized(uidToPackageCache) { uidToPackageCache.remove(uid) }
            return null
        }
        return entry.pkg
    }

    private fun cachePut(uid: Int, pkg: String?) {
        synchronized(uidToPackageCache) {
            uidToPackageCache[uid] = CacheEntry(pkg, System.currentTimeMillis())
        }
    }

    /** Главная точка входа: по remote (клиентскому) порту → uid + pkg. */
    suspend fun resolve(remotePort: Int, localHoneypotPort: Int): Pair<Int?, String?> {
        val uid = readUidFromProcNet(remotePort, localHoneypotPort)
        val pkg = uid?.let { packageForUid(it) }
        return uid to pkg
    }

    /**
     * Резолв UDP-клиента. В отличие от TCP у UDP нет ESTABLISHED — клиентский
     * сокет обычно unconnected (rem_address = 00000000:0000), поэтому искать
     * надо по совпадению local_address:<srcPort> и отбрасывать нашу own-сторону
     * (own uid), чтобы не вернуть себя.
     */
    suspend fun resolveUdp(srcPort: Int): Pair<Int?, String?> {
        val uid = readUdpUidFromProcNet(srcPort)
        val pkg = uid?.let { packageForUid(it) }
        return uid to pkg
    }

    /**
     * Парсит `/proc/net/tcp` и `/proc/net/tcp6`. Формат (упрощённо, хексы):
     *   sl  local_address  rem_address  st  tx_queue:rx_queue  tr:tm_when  retrnsmt  uid  ...
     *
     * Мы ищем строку где:
     *   - rem_address = `0100007F:<hex local honeypot port>` (для IPv4)
     *     или `...:00000000000000000000000001000000:<hex>` для IPv6-loopback
     *   - local_address = `...:<hex remote port>` (то, что было клиентским srcPort)
     *   - st = `01` (ESTABLISHED) — в момент accept() соединение уже установлено
     *
     * На практике в loopback-соединении обе стороны резидентны на устройстве, так что
     * парсинг симметричен: одна запись — наш server-side FD (uid = наш!),
     * вторая — client-side FD (uid = приложения-сканера). Нам нужна именно вторая.
     * Её отличительный признак: local_address имеет `remotePort` клиента, rem_address — наш 1080/и т.д.
     */
    private suspend fun readUidFromProcNet(remotePort: Int, localHoneypotPort: Int): Int? {
        val tcp4 = readProcNet("/proc/net/tcp") ?: ""
        val tcp6 = readProcNet("/proc/net/tcp6") ?: ""
        val combined = "$tcp4\n$tcp6"
        if (combined.isBlank()) {
            Log.w(TAG, "readUidFromProcNet: both /proc/net/tcp and tcp6 empty (Shizuku down?)")
            return null
        }

        val remoteHex = "%04X".format(remotePort)
        val honeypotHex = "%04X".format(localHoneypotPort)
        Log.d(TAG, "searching tcp tables for local:*$remoteHex → rem:*$honeypotHex (tcp4=${tcp4.length}B tcp6=${tcp6.length}B)")

        for (raw in combined.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("sl ")) continue
            // Формат: индекс local_hex:port rem_hex:port st ... uid ...
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 8) continue
            val local = parts[1]
            val remote = parts[2]
            // client-side: local_address заканчивается на :<remotePort>, rem_address заканчивается на :<honeypotPort>
            if (!local.endsWith(":$remoteHex", ignoreCase = true)) continue
            if (!remote.endsWith(":$honeypotHex", ignoreCase = true)) continue
            // uid — 8-й столбец (индекс 7)
            val uidStr = parts[7]
            val uid = uidStr.toIntOrNull() ?: continue
            Log.d(TAG, "matched proc line: local=$local rem=$remote uid=$uid")
            return uid
        }
        Log.w(TAG, "no tcp row matched :$remoteHex → :$honeypotHex")
        return null
    }

    private suspend fun readProcNet(path: String): String? {
        // `cat` обычно есть, но на всякий случай fallback через `/system/bin/toybox cat`
        return shizukuManager.runCommandWithOutput("cat", path)
            ?.takeIf { !it.startsWith("ERROR:") }
    }

    private suspend fun readUdpUidFromProcNet(srcPort: Int): Int? {
        val udp4 = readProcNet("/proc/net/udp") ?: ""
        val udp6 = readProcNet("/proc/net/udp6") ?: ""
        val combined = "$udp4\n$udp6"
        if (combined.isBlank()) {
            Log.w(TAG, "readUdpUidFromProcNet: /proc/net/udp[6] пусты — Shizuku down?")
            return null
        }

        val srcPortHex = "%04X".format(srcPort)
        val myUid = Process.myUid()

        for (raw in combined.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("sl ")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 8) continue
            val local = parts[1]
            if (!local.endsWith(":$srcPortHex", ignoreCase = true)) continue
            val uid = parts[7].toIntOrNull() ?: continue
            if (uid == myUid) continue  // свой же server-side сокет
            Log.d(TAG, "udp matched: local=$local uid=$uid")
            return uid
        }
        Log.w(TAG, "no udp row matched :$srcPortHex")
        return null
    }

    private suspend fun packageForUid(uid: Int): String? {
        cacheGet(uid)?.let { return it }
        // Системные uid (< 10000) — это не приложение, а демоны/сам шелл.
        // Для них pm list всё равно ничего не вернёт.
        if (uid < 10000) {
            cachePut(uid, null)
            return null
        }
        val out = shizukuManager.runCommandWithOutput("pm", "list", "packages", "--uid", uid.toString())
        // Формат: "package:com.example.app" (по одной строке на пакет, обычно одна)
        val pkg = out
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
        Log.d(TAG, "pm list packages --uid $uid → pkg=$pkg (raw=${out?.take(200)})")
        cachePut(uid, pkg)
        return pkg
    }

    private companion object {
        const val CACHE_MAX_SIZE = 256
        const val CACHE_TTL_MS = 60 * 60 * 1000L  // 1h
    }
}
