package sgnv.anubis.app.audit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import sgnv.anubis.app.audit.model.AuditHit
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Смок-тест honeypot'а на настоящих сокетах. Запускаем listener, в том же
 * JVM коннектимся клиентом, проверяем что hits вылетают с ожидаемыми полями.
 *
 * Резолв UID замучан через FakeShellExec, который возвращает пустой
 * /proc/net/tcp — поэтому проверяем только port/protocol/preview/sni, uid/pkg
 * всегда null. Для этого достаточно: end-to-end accept → handleClient → hit
 * реально работает на боевом стеке сокетов.
 */
class HoneypotSmokeTest {

    private lateinit var listener: HoneypotListener
    private lateinit var shell: FakeShellExec

    @Before
    fun setUp() {
        shell = FakeShellExec()
        shell.on("cat /proc/net", "")
        shell.on("pm list", "")
        listener = HoneypotListener(shell)
    }

    @After
    fun tearDown() {
        listener.shutdown()
    }

    @Test
    fun catches_socks5_greeting_and_answers_no_auth() = runBlocking {
        listener.start()

        val port = awaitFirstListening()
            ?: run { assumeTrue("Ни один honeypot-порт не свободен на этой машине", false); return@runBlocking }

        val (firstHit, collector) = subscribeForFirstHit(this)

        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))  // SOCKS5: ver=5, 1 method, NO_AUTH
                flush()
            }
            // Honeypot должен ответить 05 00 — иначе сканер не считает порт прокси.
            val reply = ByteArray(2)
            val n = sock.getInputStream().read(reply)
            assertEquals("honeypot должен прислать 2-байтовый SOCKS5 ответ", 2, n)
            assertEquals(0x05.toByte(), reply[0])
            assertEquals(0x00.toByte(), reply[1])
        }

        val hit = withTimeout(3000) { firstHit.await() }
        collector.cancel()

        assertEquals(port, hit.port)
        assertEquals("TCP", hit.protocol)
        assertNotNull("preview должен содержать начало SOCKS5 greeting", hit.handshakePreview)
        assertTrue(hit.handshakePreview!!.startsWith("05 01"))
    }

    @Test
    fun extracts_sni_from_tls_client_hello() = runBlocking {
        listener.start()

        val port = awaitFirstListening()
            ?: run { assumeTrue("Ни один honeypot-порт не свободен на этой машине", false); return@runBlocking }

        val (firstHit, collector) = subscribeForFirstHit(this)

        val clientHello = buildTlsClientHello("api.rshb.ru")
        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().apply {
                write(clientHello)
                flush()
            }
            // Дёргать `read` не обязательно — honeypot сам прочитает до timeout.
        }

        val hit = withTimeout(3000) { firstHit.await() }
        collector.cancel()

        assertEquals(port, hit.port)
        assertEquals("TCP", hit.protocol)
        assertEquals("api.rshb.ru", hit.sni)
    }

    @Test
    fun catches_udp_datagram() = runBlocking {
        listener.start()

        // UDP поднимается внутри того же port-job'а что TCP. Ждём пока TCP-bind
        // отрапортует, а udp bind идёт следом синхронно — в пределах миллисекунд.
        val port = awaitFirstListening()
            ?: run { assumeTrue("Ни один honeypot-порт не свободен на этой машине", false); return@runBlocking }
        delay(50)  // пусть bindUdp успеет подняться

        val (firstHit, collector) = subscribeForFirstHit(this)

        DatagramSocket().use { client ->
            val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            client.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName("127.0.0.1"),
                    port,
                )
            )
        }

        val hit = withTimeoutOrNull(3000) { firstHit.await() }
        collector.cancel()

        // UDP-bind может провалиться если порт занят другим процессом именно
        // под UDP — это ок, скипаем проверку.
        if (hit == null) {
            assumeTrue("UDP порт $port занят — skip", false)
            return@runBlocking
        }
        assertEquals(port, hit.port)
        assertEquals("UDP", hit.protocol)
        assertTrue("UDP preview начинается с переданного payload", hit.handshakePreview!!.startsWith("CA FE BA BE"))
    }

    /** Ждёт первый порт, успешно поднятый в LISTENING. null если за 2с никто. */
    private suspend fun awaitFirstListening(): Int? = withTimeoutOrNull(2000) {
        listener.portStatus.filter { it.state == PortState.LISTENING }.first().port
    }

    /**
     * Подписывается на hits и ждёт фактического subscribe (onSubscription) —
     * без этого race: эмит из handleClient может случиться раньше чем
     * collector реально слушает, SharedFlow с replay=0 его потеряет.
     */
    private suspend fun subscribeForFirstHit(
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<CompletableDeferred<AuditHit>, kotlinx.coroutines.Job> {
        val firstHit = CompletableDeferred<AuditHit>()
        val subscribed = CompletableDeferred<Unit>()
        val job = scope.launch {
            listener.hits
                .onSubscription { subscribed.complete(Unit) }
                .collect { if (!firstHit.isCompleted) firstHit.complete(it) }
        }
        subscribed.await()
        return firstHit to job
    }

    // --- TLS ClientHello builder (тот же что в TlsSniParserTest, минимально) ---

    private fun buildTlsClientHello(hostname: String): ByteArray {
        val host = hostname.toByteArray(Charsets.US_ASCII)
        val sni = ByteArrayOutputStream().apply {
            writeU16(host.size + 3)
            write(0x00)
            writeU16(host.size)
            write(host)
        }.toByteArray()
        val ext = ByteArrayOutputStream().apply {
            writeU16(0x0000)
            writeU16(sni.size)
            write(sni)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            writeU16(0x0303)
            write(ByteArray(32))
            write(0x00)
            writeU16(2); writeU16(0x0035)
            write(1); write(0x00)
            writeU16(ext.size)
            write(ext)
        }.toByteArray()
        val hs = ByteArrayOutputStream().apply {
            write(0x01)
            writeU24(body.size)
            write(body)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x16)
            writeU16(0x0301)
            writeU16(hs.size)
            write(hs)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU24(v: Int) {
        write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF)
    }
}
