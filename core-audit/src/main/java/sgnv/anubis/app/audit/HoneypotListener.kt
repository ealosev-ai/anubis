package sgnv.anubis.app.audit

import android.util.Log
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.shizuku.ShellExec
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
    private val shell: ShellExec,
    native: NativeUidResolver? = null,
    packages: PackageResolver? = null,
) {
    private val resolver = UidResolver(shell, native, packages)
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
                resolver.resolveUdp(srcPort, localHoneypotPort = honeypotPort)
            } catch (_: Exception) {
                null to null
            }

            val preview = if (packet.length > 0) {
                packet.data.copyOf(minOf(packet.length, 256)).toSmartPreview()
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

        // Читаем preview, парсим протокол, играем handshake до конца чтобы
        // сканер пошёл дальше и выдал ClientHello с целевым SNI. Раньше мы
        // обрывались после greeting — продвинутые детекторы (yourvpndead,
        // встроенные банковские чеки) классифицировали порт как фейковый и
        // бросали. Сейчас: CONNECT succeeded → клиент шлёт TLS → ловим SNI.
        var preview: String? = null
        var sni: String? = null
        try {
            // 1200 мс — даёт время на 3-phase SOCKS5 (greeting + connect + app-data),
            // если клиент не TLS — второй read вернётся по таймауту, preview уже есть.
            client.soTimeout = 1200
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buf = ByteArray(2048)
            val n = input.read(buf)
            if (n > 0) {
                preview = buf.copyOf(minOf(n, 256)).toSmartPreview()

                when {
                    // TLS ClientHello на голом порту (например банк идёт прямо
                    // в HTTPS на наш 8080). Парсим SNI сразу.
                    n >= 6 && buf[0] == 0x16.toByte() && buf[1] == 0x03.toByte() -> {
                        sni = try { extractSni(buf, n) } catch (_: Exception) { null }
                    }

                    // SOCKS5: greeting (05 NN methods...) — полный handshake.
                    n >= 3 && buf[0] == 0x05.toByte() && buf[1] > 0 -> {
                        sni = playSocks5(input, output)
                    }

                    // HTTP CONNECT: «CONNECT host:443 HTTP/1.1\r\n...»
                    n >= 7 && buf[0] == 'C'.code.toByte() && buf[1] == 'O'.code.toByte() -> {
                        sni = playHttpConnect(input, output)
                    }
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

    /**
     * Полноценный SOCKS5 handshake. Сканер: `05 01 00` (один method — no auth),
     * мы: `05 00`, сканер: `05 01 00 ATYP ADDR PORT` (CONNECT), мы: `05 00 00 01 0 0`.
     * После этого клиент слепо шлёт application data — обычно TLS ClientHello
     * на целевой хост. Мы читаем ClientHello и извлекаем target-SNI.
     *
     * Прокси мы разумеется НЕ делаем — данные никуда не уходят, мы просто
     * притворяемся что есть backend. Для сканера это «живой прокси», для
     * нас — forensic data о том, куда хотели туннелировать.
     */
    private fun playSocks5(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ): String? {
        // Greeting: принимаем и отвечаем «no-auth».
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // Далее клиент шлёт CONNECT request: [VER CMD RSV ATYP ADDR... PORT(2)]
        // ATYP = 0x01 (IPv4, 4b), 0x03 (domain, len-prefix), 0x04 (IPv6, 16b)
        val reqBuf = ByteArray(262)  // max: 4 header + 1 len + 255 domain + 2 port
        val reqN = try { input.read(reqBuf) } catch (_: Exception) { return null }
        if (reqN < 7 || reqBuf[0] != 0x05.toByte()) return null

        // Ответ «succeeded» — `05 00 00 01 0.0.0.0 :0000`. BND.ADDR/PORT
        // ноль — стандартно для серверов которые не раскрывают свой upstream.
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        // Теперь клиент уверен что мы подключились, и начинает слать данные
        // на «backend». Читаем — если TLS ClientHello, вытащим SNI.
        val appBuf = ByteArray(2048)
        val appN = try { input.read(appBuf) } catch (_: Exception) { return null }
        if (appN >= 6 && appBuf[0] == 0x16.toByte() && appBuf[1] == 0x03.toByte()) {
            return try { extractSni(appBuf, appN) } catch (_: Exception) { null }
        }
        return null
    }

    /**
     * HTTP CONNECT proxy — `CONNECT target:443 HTTP/1.1\r\nHost: ...\r\n\r\n`.
     * Отвечаем 200 OK, ждём TLS ClientHello, вытаскиваем SNI.
     *
     * Отдельный плюс: target из CONNECT-строки сам по себе уже forensic
     * data — но в SNI это обычно тот же хост, просто с более чистой строкой.
     */
    private fun playHttpConnect(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ): String? {
        // Мы уже прочитали первые байты в buf — драинием остаток заголовков
        // до "\r\n\r\n". В реальности первое `input.read` в 99% случаев вернул
        // всю `CONNECT` запроса, но для надёжности делаем доп-read.
        try {
            val extra = ByteArray(1024)
            input.read(extra)  // best-effort, timeout держит от повисания
        } catch (_: Exception) {}

        // 200 OK — клиент пойдёт делать TLS handshake.
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()

        // TLS ClientHello на target.
        val appBuf = ByteArray(2048)
        val appN = try { input.read(appBuf) } catch (_: Exception) { return null }
        if (appN >= 6 && appBuf[0] == 0x16.toByte() && appBuf[1] == 0x03.toByte()) {
            return try { extractSni(appBuf, appN) } catch (_: Exception) { null }
        }
        return null
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
            3128, 8080, 8888, 8118,       // HTTP CONNECT (+ Polipo 8118)
            10808, 10809, 10900,          // xray-core (+ распространённый альт)
            7890,                         // Clash/mihomo
            9090,                         // Clash REST API
            2080,                         // sing-box
            4444,                         // i2p proxy (yourvpndead сканит)
            1090,                         // альт-SOCKS5 (shadowsocks fallback)
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

/**
 * HEX первых 32 байт + ASCII до 256. HTTP-запросы читаются глазами
 * (видим Host/User-Agent), бинарь (TLS ClientHello) сохраняет hex
 * для ручного разбора. 32-байтный hex оставлен для обратной совместимости
 * с историческими записями в БД.
 */
private fun ByteArray.toSmartPreview(): String {
    val hexPart = copyOf(minOf(size, 32)).toHexPreview()
    val asciiPart = buildString(size) {
        for (i in 0 until minOf(size, 256)) {
            val c = this@toSmartPreview[i].toInt() and 0xFF
            append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
    }
    return "$hexPart | $asciiPart"
}
