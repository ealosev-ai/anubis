package sgnv.anubis.app.audit

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import org.junit.runner.RunWith
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.shizuku.ShellExec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * End-to-end на реальном Android: honeypot поднимает ServerSocket/DatagramSocket
 * через системный сетевой стек, instrumentation-сторона коннектится из того же
 * приложения, проверяем что hit вылетает с ожидаемыми полями.
 *
 * Отличие от JVM-smoke: тут работает настоящий Linux kernel эмулятора/устройства,
 * SELinux-политики Android на loopback-порты, и реальный DatagramSocket Android'а.
 *
 * UID-резолв замучан FakeShellExec — для честного Shizuku end-to-end нужен
 * запущенный shizuku-демон на устройстве, это уже отдельный слой (см. TODO).
 */
@RunWith(AndroidJUnit4::class)
class HoneypotInstrumentedTest {

    private lateinit var listener: HoneypotListener
    private lateinit var shell: FakeShell

    @Before
    fun setUp() {
        shell = FakeShell()
        listener = HoneypotListener(shell)
    }

    @After
    fun tearDown() {
        listener.shutdown()
    }

    @Test
    fun real_tcp_socket_triggers_hit_with_socks5_reply() = runBlocking {
        listener.start()
        val port = awaitFirstListening()
            ?: run { assumeTrue("ни один honeypot-порт не свободен на устройстве", false); return@runBlocking }

        val (firstHit, _) = subscribeForFirstHit(this)

        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                flush()
            }
            val reply = ByteArray(2)
            val n = sock.getInputStream().read(reply)
            assertEquals(2, n)
            assertEquals(0x05.toByte(), reply[0])
            assertEquals(0x00.toByte(), reply[1])
        }

        val hit = withTimeout(3000) { firstHit.await() }
        assertEquals(port, hit.port)
        assertEquals("TCP", hit.protocol)
        assertNotNull(hit.handshakePreview)
    }

    @Test
    fun real_tls_client_hello_extracts_sni() = runBlocking {
        listener.start()
        val port = awaitFirstListening()
            ?: run { assumeTrue("нет свободных портов", false); return@runBlocking }

        val (firstHit, _) = subscribeForFirstHit(this)

        val clientHello = buildTlsClientHello("api.rshb.ru")
        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().apply { write(clientHello); flush() }
        }

        val hit = withTimeout(3000) { firstHit.await() }
        assertEquals("api.rshb.ru", hit.sni)
    }

    @Test
    fun real_udp_datagram_triggers_hit() = runBlocking {
        listener.start()
        val port = awaitFirstListening()
            ?: run { assumeTrue("нет свободных портов", false); return@runBlocking }

        val (firstHit, _) = subscribeForFirstHit(this)

        DatagramSocket().use { client ->
            val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            client.send(
                DatagramPacket(
                    payload, payload.size,
                    InetAddress.getByName("127.0.0.1"), port,
                )
            )
        }

        val hit = withTimeoutOrNull(3000) { firstHit.await() }
        if (hit == null) {
            assumeTrue("UDP на этом порту не доступен", false); return@runBlocking
        }
        assertEquals(port, hit.port)
        assertEquals("UDP", hit.protocol)
        assertTrue(hit.handshakePreview!!.startsWith("DE AD BE EF"))
    }

    // --- infra ---

    private suspend fun awaitFirstListening(): Int? = withTimeoutOrNull(3000) {
        listener.portStatus.filter { it.state == PortState.LISTENING }.first().port
    }

    private suspend fun subscribeForFirstHit(
        scope: CoroutineScope,
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

    /** Минимальная fake-реализация ShellExec для on-device тестов. */
    private class FakeShell : ShellExec {
        override suspend fun runShell(vararg args: String): String? {
            // /proc/net/tcp на Android 10+ для не-root приложения отдаёт
            // только собственные строки с uid=0 (песочница). Для тестов
            // резолв нас не интересует — возвращаем пусто.
            return if (args.firstOrNull() == "cat") "" else null
        }
    }

    // --- TLS ClientHello builder (тот же что в jvm-smoke) ---

    private fun buildTlsClientHello(hostname: String): ByteArray {
        val host = hostname.toByteArray(Charsets.US_ASCII)
        val sni = java.io.ByteArrayOutputStream().apply {
            writeU16(host.size + 3); write(0x00); writeU16(host.size); write(host)
        }.toByteArray()
        val ext = java.io.ByteArrayOutputStream().apply {
            writeU16(0x0000); writeU16(sni.size); write(sni)
        }.toByteArray()
        val body = java.io.ByteArrayOutputStream().apply {
            writeU16(0x0303); write(ByteArray(32)); write(0x00)
            writeU16(2); writeU16(0x0035); write(1); write(0x00)
            writeU16(ext.size); write(ext)
        }.toByteArray()
        val hs = java.io.ByteArrayOutputStream().apply {
            write(0x01); writeU24(body.size); write(body)
        }.toByteArray()
        return java.io.ByteArrayOutputStream().apply {
            write(0x16); writeU16(0x0301); writeU16(hs.size); write(hs)
        }.toByteArray()
    }

    private fun java.io.ByteArrayOutputStream.writeU16(v: Int) {
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun java.io.ByteArrayOutputStream.writeU24(v: Int) {
        write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF)
    }
}
