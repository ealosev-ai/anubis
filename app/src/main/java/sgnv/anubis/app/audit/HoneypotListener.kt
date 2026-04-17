package sgnv.anubis.app.audit

import android.util.Log
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "AuditHoneypot"

/**
 * Honeypot-ловушка для детекторов VPN.
 *
 * Методичка Минцифры (apr 2026) советует приложениям сканить localhost на типовых SOCKS5/HTTP-прокси
 * и Tor-портах, чтобы «спалить» пользователя через VLESS-клиент с открытым прокси.
 *
 * Мы поднимаем ServerSocket на тех же портах и логируем, кто к нам подключается.
 *
 * **Важно:** если VPN-клиент (v2rayNG/Hiddify) уже запущен, он держит один из этих портов —
 * `bind()` провалится с BindException. Это нормально: такой порт помечаем как занятый
 * и не слушаем. Аудит эффективнее всего запускать при VPN OFF.
 *
 * Порты взяты из методички:
 *   - 1080/9000/5555 — SOCKS5
 *   - 3128/8080      — HTTP CONNECT
 *   - 9050           — Tor SOCKS
 */
class HoneypotListener(
    private val shizukuManager: ShizukuManager,
) {
    private val resolver = UidResolver(shizukuManager)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _hits = MutableSharedFlow<AuditHit>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val hits: SharedFlow<AuditHit> = _hits.asSharedFlow()

    private val _portStatus = MutableSharedFlow<PortStatus>(
        replay = PORTS.size,
        extraBufferCapacity = PORTS.size,
    )
    val portStatus: SharedFlow<PortStatus> = _portStatus.asSharedFlow()

    // Визуальный дебаг-статус для UI (Honor режет logcat, logcat у нас слеп).
    private val _debug = kotlinx.coroutines.flow.MutableStateFlow(HoneypotDebug())
    val debug: kotlinx.coroutines.flow.StateFlow<HoneypotDebug> = _debug

    /**
     * Состояние listener'а как StateFlow. Это важно именно для singleton-сценария:
     * listener живёт в AnubisApp, несколько ViewModel-инстансов (при recreate Activity)
     * должны видеть реальное running-состояние, а не каждая своё локальное.
     */
    private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running

    private val serverSockets = mutableListOf<ServerSocket>()
    private val portJobs = mutableListOf<Job>()

    private fun bumpDebug(transform: (HoneypotDebug) -> HoneypotDebug) {
        // ВАЖНО: через StateFlow.update, а не `_debug.value = transform(_debug.value)`.
        // Последнее — read-modify-write без блокировки, и 6 параллельных корутин
        // (по одной на порт) затирают друг другу `portsListening + port`, в итоге
        // в set остаётся только один порт из шести. .update делает atomic CAS-loop.
        _debug.update(transform)
    }

    /** Запустить прослушивание всех портов. Неуспешные `bind()` просто пропускаем. */
    fun start() {
        if (_running.value) return
        _running.value = true
        _debug.value = HoneypotDebug(startedAtMs = System.currentTimeMillis())
        Log.e(TAG, "start(): binding ${PORTS.size} ports on 127.0.0.1")
        val loopback = InetAddress.getByName("127.0.0.1")

        for (port in PORTS) {
            val job = scope.launch {
                val srv = try {
                    ServerSocket(port, /* backlog = */ 4, loopback)
                } catch (e: Exception) {
                    Log.e(TAG, "bind port=$port FAILED: ${e.message}")
                    bumpDebug { it.copy(
                        portsFailed = it.portsFailed + port,
                        lastError = "bind $port failed: ${e.message}",
                    ) }
                    _portStatus.tryEmit(PortStatus(port, PortState.BUSY, e.message))
                    return@launch
                }
                synchronized(serverSockets) { serverSockets += srv }
                Log.e(TAG, "bind port=$port OK, listening")
                bumpDebug { it.copy(portsListening = it.portsListening + port) }
                _portStatus.tryEmit(PortStatus(port, PortState.LISTENING, null))
                acceptLoop(srv, port)
            }
            portJobs += job
        }
    }

    /** Остановить, закрыть все сокеты. */
    fun stop() {
        if (!_running.value) return
        _running.value = false
        Log.i(TAG, "stop()")
        synchronized(serverSockets) {
            for (s in serverSockets) {
                try { s.close() } catch (_: Exception) {}
            }
            serverSockets.clear()
        }
        for (j in portJobs) j.cancel()
        portJobs.clear()
        for (p in PORTS) _portStatus.tryEmit(PortStatus(p, PortState.STOPPED, null))
    }

    /** Освободить scope (при shutdown приложения). */
    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(srv: ServerSocket, honeypotPort: Int) {
        while (_running.value && !srv.isClosed) {
            val client: Socket = try {
                withContext(Dispatchers.IO) { srv.accept() }
            } catch (_: Exception) {
                return // сокет закрыт — выходим
            }
            // Параллельно обрабатываем каждое подключение, чтоб accept() не блокировался
            scope.launch { handleClient(client, honeypotPort) }
        }
    }

    private suspend fun handleClient(client: Socket, honeypotPort: Int) {
        val remotePort = client.port
        val timestamp = System.currentTimeMillis()
        bumpDebug { it.copy(accepts = it.accepts + 1, lastAcceptMs = timestamp, lastAcceptPort = honeypotPort) }
        Log.e(TAG, "accept port=$honeypotPort from :$remotePort")

        // ВАЖНО: резолвим uid ПОКА соединение в ESTABLISHED.
        // После `client.close()` client-side уходит в TIME_WAIT, и ядро Linux для
        // timewait-сокетов всегда печатает uid=0 (см. `get_timewait4_sock()` в
        // net/ipv4/tcp_ipv4.c — поле uid жёстко захардкожено в 0). Если дёрнуть
        // /proc/net/tcp после close() — получим uid=0 и пустой pkg, даже если
        // всё остальное работает идеально.
        val (uid, pkg) = try {
            resolver.resolve(remotePort = remotePort, localHoneypotPort = honeypotPort)
        } catch (e: Exception) {
            Log.e(TAG, "resolver.resolve threw: ${e.message}")
            bumpDebug { it.copy(lastError = "resolve failed: ${e.message}") }
            null to null
        }

        // Теперь уже можно прочитать preview и закрыть — uid уже зарезолвлен.
        // Если сканер послал SOCKS5 greeting (05 01 00) — отвечаем 05 00
        // (ver=5, method=NO_AUTH). Без этого продвинутые сканеры (yourvpndead,
        // RKNHardering) не классифицируют порт как реальный SOCKS5-прокси и
        // могут не поднять тревогу. С ответом — сканер идёт дальше в handshake,
        // мы ловим полноценный hit.
        val preview: String? = try {
            client.soTimeout = 300
            val buf = ByteArray(16)
            val n = client.getInputStream().read(buf)
            if (n >= 3 && buf[0] == 0x05.toByte() && buf[1] > 0) {
                try {
                    client.getOutputStream().write(byteArrayOf(0x05, 0x00))
                    client.getOutputStream().flush()
                } catch (_: Exception) {}
            }
            if (n > 0) buf.copyOf(n).toHexPreview() else null
        } catch (_: Exception) {
            null
        }
        try { client.close() } catch (_: Exception) {}
        bumpDebug {
            it.copy(
                resolvedUids = if (uid != null) it.resolvedUids + 1 else it.resolvedUids,
                resolvedPkgs = if (pkg != null) it.resolvedPkgs + 1 else it.resolvedPkgs,
            )
        }
        Log.e(
            TAG,
            "hit port=$honeypotPort srcPort=$remotePort uid=$uid pkg=$pkg preview=$preview"
        )

        _hits.tryEmit(
            AuditHit(
                timestampMs = timestamp,
                port = honeypotPort,
                uid = uid,
                packageName = pkg,
                handshakePreview = preview,
            )
        )
    }

    companion object {
        /**
         * Порты из методички Минцифры (апрель 2026) + дефолтные порты
         * популярных VPN-клиентов. Расширенный набор по данным
         * RKNHardering / yourvpndead:
         *
         * - 1080, 9000, 5555         — SOCKS5 (методичка)
         * - 9050, 9051, 9150         — Tor SOCKS / Control / Browser
         * - 3128, 8080, 8888         — HTTP CONNECT (методичка)
         * - 10808, 10809             — xray-core default (SOCKS5/HTTP)
         * - 7890                     — Clash/mihomo SOCKS
         * - 9090                     — Clash REST API (yourvpndead проверяет!)
         * - 2080                     — sing-box default
         */
        val PORTS = listOf(
            1080, 9000, 5555,             // SOCKS5 (методичка)
            9050, 9051, 9150,             // Tor
            3128, 8080, 8888,             // HTTP CONNECT
            10808, 10809,                 // xray-core
            7890,                         // Clash/mihomo
            9090,                         // Clash REST API
            2080,                         // sing-box
        )
    }
}

enum class PortState { LISTENING, BUSY, STOPPED }

data class PortStatus(val port: Int, val state: PortState, val error: String?)

/** Визуальный дебаг-срез для UI — показывает, на каком этапе что сломалось. */
data class HoneypotDebug(
    val startedAtMs: Long? = null,
    val portsListening: Set<Int> = emptySet(),
    val portsFailed: Set<Int> = emptySet(),
    val accepts: Int = 0,
    val lastAcceptMs: Long? = null,
    val lastAcceptPort: Int? = null,
    val resolvedUids: Int = 0,
    val resolvedPkgs: Int = 0,
    val lastError: String? = null,
)

private fun ByteArray.toHexPreview(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
