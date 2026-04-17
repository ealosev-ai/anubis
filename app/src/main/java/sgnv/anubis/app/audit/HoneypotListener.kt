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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val datagramSockets = mutableListOf<DatagramSocket>()
    private val portJobs = mutableListOf<Job>()

    private fun bumpDebug(transform: (HoneypotDebug) -> HoneypotDebug) {
        // ВАЖНО: через StateFlow.update, а не `_debug.value = transform(_debug.value)`.
        // Последнее — read-modify-write без блокировки, и 6 параллельных корутин
        // (по одной на порт) затирают друг другу `portsListening + port`, в итоге
        // в set остаётся только один порт из шести. .update делает atomic CAS-loop.
        _debug.update(transform)
    }

    /**
     * Запустить прослушивание всех портов на обоих loopback (IPv4 + IPv6).
     * Неуспешные `bind()` просто пропускаем. Порт считается LISTENING, если
     * поднялся хоть один из двух — сканеру достаточно одного ответа.
     */
    fun start() {
        if (_running.value) return
        _running.value = true
        _debug.value = HoneypotDebug(startedAtMs = System.currentTimeMillis())
        Log.e(TAG, "start(): binding ${PORTS.size} ports on 127.0.0.1 + ::1")
        val loopback4 = InetAddress.getByName("127.0.0.1")
        // ::1 — IPv6 loopback. Некоторые сканеры подключаются именно туда,
        // особенно из приложений на Netty/OkHttp с DNS-ответом на localhost.
        val loopback6 = try { InetAddress.getByName("::1") } catch (_: Exception) { null }

        for (port in PORTS) {
            val job = scope.launch {
                val v4 = bind(port, loopback4, label = "v4")
                val v6 = loopback6?.let { bind(port, it, label = "v6") }
                val state = when {
                    v4 != null || v6 != null -> PortState.LISTENING
                    else -> PortState.BUSY
                }
                if (state == PortState.LISTENING) {
                    bumpDebug { it.copy(portsListening = it.portsListening + port) }
                } else {
                    bumpDebug { it.copy(portsFailed = it.portsFailed + port) }
                }
                _portStatus.tryEmit(PortStatus(port, state, null))
                // accept-циклы бегут параллельно на каждой address family.
                coroutineScopeLaunch(v4, port)
                coroutineScopeLaunch(v6, port)

                // UDP listeners — для QUIC/WireGuard-детектов методички.
                // Не обязательно чтобы все TCP-порты бились на UDP, просто
                // пробуем; нам важен не bind-успех, а накопление уникальных
                // accept'ов по обоим протоколам.
                val u4 = bindUdp(port, loopback4, label = "u4")
                val u6 = loopback6?.let { bindUdp(port, it, label = "u6") }
                udpLoopLaunch(u4, port)
                udpLoopLaunch(u6, port)
            }
            portJobs += job
        }
    }

    private fun bindUdp(port: Int, addr: InetAddress, label: String): DatagramSocket? {
        return try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(addr, port))
            }
            synchronized(datagramSockets) { datagramSockets += sock }
            Log.e(TAG, "udp bind port=$port/$label OK")
            sock
        } catch (e: Exception) {
            Log.e(TAG, "udp bind port=$port/$label FAILED: ${e.message}")
            null
        }
    }

    private fun udpLoopLaunch(sock: DatagramSocket?, honeypotPort: Int) {
        if (sock == null) return
        scope.launch { udpReceiveLoop(sock, honeypotPort) }
    }

    private fun bind(port: Int, addr: InetAddress, label: String): ServerSocket? {
        return try {
            val srv = ServerSocket(port, /* backlog = */ 4, addr)
            synchronized(serverSockets) { serverSockets += srv }
            Log.e(TAG, "bind port=$port/$label OK")
            srv
        } catch (e: Exception) {
            Log.e(TAG, "bind port=$port/$label FAILED: ${e.message}")
            bumpDebug { it.copy(lastError = "bind $port/$label: ${e.message}") }
            null
        }
    }

    private fun coroutineScopeLaunch(srv: ServerSocket?, honeypotPort: Int) {
        if (srv == null) return
        scope.launch { acceptLoop(srv, honeypotPort) }
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
        synchronized(datagramSockets) {
            for (s in datagramSockets) {
                try { s.close() } catch (_: Exception) {}
            }
            datagramSockets.clear()
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

    private suspend fun udpReceiveLoop(sock: DatagramSocket, honeypotPort: Int) {
        val buf = ByteArray(4096)
        while (_running.value && !sock.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                withContext(Dispatchers.IO) { sock.receive(packet) }
            } catch (_: Exception) {
                return
            }
            val timestamp = System.currentTimeMillis()
            val srcPort = packet.port
            bumpDebug { it.copy(accepts = it.accepts + 1, lastAcceptMs = timestamp, lastAcceptPort = honeypotPort) }
            Log.e(TAG, "udp recv port=$honeypotPort from :$srcPort len=${packet.length}")

            // UID резолв синхронно — UDP-сокет клиента обычно живёт миг,
            // после sendto() может быть уже закрыт. Пробуем сразу.
            val (uid, pkg) = try {
                resolver.resolveUdp(srcPort)
            } catch (_: Exception) {
                null to null
            }

            val preview = if (packet.length > 0) {
                packet.data.copyOf(minOf(packet.length, 32)).toHexPreview()
            } else null

            bumpDebug {
                it.copy(
                    resolvedUids = if (uid != null) it.resolvedUids + 1 else it.resolvedUids,
                    resolvedPkgs = if (pkg != null) it.resolvedPkgs + 1 else it.resolvedPkgs,
                )
            }

            _hits.tryEmit(
                AuditHit(
                    timestampMs = timestamp,
                    port = honeypotPort,
                    uid = uid,
                    packageName = pkg,
                    handshakePreview = preview,
                    sni = null,
                    protocol = "UDP",
                )
            )
        }
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
        // Читаем до 2 KiB: достаточно чтобы в TLS ClientHello поместился SNI
        // (hostname обычно в первом extension). На SOCKS5 greeting хватает и 3
        // байт, так что большие буферы не мешают.
        var preview: String? = null
        var sni: String? = null
        try {
            client.soTimeout = 400
            val buf = ByteArray(2048)
            val n = client.getInputStream().read(buf)
            if (n > 0) {
                preview = buf.copyOf(minOf(n, 32)).toHexPreview()

                // TLS ClientHello: `16 03 XX` + ещё байты handshake.
                // Парсер устойчив к мусору — либо SNI, либо null.
                if (n >= 6 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte()) {
                    sni = try { extractSni(buf, n) } catch (_: Exception) { null }
                }

                // SOCKS5 greeting (05 01 NN) — отвечаем 05 00 (no auth),
                // чтобы сканеры-детекторы классифицировали порт как реальный
                // SOCKS5. Без ответа yourvpndead/RKNHardering не триггерят тревогу.
                if (n >= 3 && buf[0] == 0x05.toByte() && buf[1] > 0) {
                    try {
                        client.getOutputStream().write(byteArrayOf(0x05, 0x00))
                        client.getOutputStream().flush()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // соединение закрыли до того как мы успели прочитать — норм
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
            "hit port=$honeypotPort srcPort=$remotePort uid=$uid pkg=$pkg sni=$sni preview=$preview"
        )

        _hits.tryEmit(
            AuditHit(
                timestampMs = timestamp,
                port = honeypotPort,
                uid = uid,
                packageName = pkg,
                handshakePreview = preview,
                sni = sni,
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
