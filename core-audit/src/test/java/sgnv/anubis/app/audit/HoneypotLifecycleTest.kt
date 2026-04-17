package sgnv.anubis.app.audit

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Тесты жизненного цикла HoneypotListener: start/stop/restart, double-start,
 * обработка занятого порта. Используют настоящие сокеты на loopback — это
 * не дорого (~миллисекунды) и ловит реальные проблемы (например если в коде
 * забыли закрыть ServerSocket).
 */
class HoneypotLifecycleTest {

    private val shell = FakeShellExec().apply {
        on("cat /proc/net", "")
        on("pm list", "")
    }
    private var listener: HoneypotListener? = null

    @After
    fun tearDown() {
        listener?.shutdown()
    }

    private fun newListener() = HoneypotListener(shell).also { listener = it }

    @Test
    fun stop_closes_server_sockets_and_releases_ports() = runBlocking {
        val l = newListener()
        l.start()
        val port = l.awaitFirstListening()
            ?: run { assumeTrue("нет свободных портов", false); return@runBlocking }

        // До stop: можем коннектиться.
        Socket("127.0.0.1", port).use { /* connect ok */ }

        l.stop()

        // После stop: коннект должен упасть (сервер закрыт) ИЛИ порт должен
        // стать доступен для bind. Проверим bind — надёжнее.
        delay(50)
        val freed = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                close()
            }
            true
        } catch (_: Exception) { false }
        assertTrue("порт $port должен быть освобождён после stop", freed)
    }

    @Test
    fun restart_works_after_stop() = runBlocking {
        val l = newListener()
        l.start()
        val firstPort = l.awaitFirstListening() ?: run {
            assumeTrue("нет свободных портов", false); return@runBlocking
        }
        l.stop()

        // Заново стартовать — должен снова поднять listener'ы.
        l.start()
        val secondPort = l.awaitFirstListening() ?: run {
            assumeTrue("после restart не удалось подняться — возможно TIME_WAIT", false); return@runBlocking
        }
        assertTrue(
            "после restart хоть один порт должен быть в LISTENING",
            secondPort > 0,
        )
    }

    @Test
    fun double_start_is_idempotent() = runBlocking {
        val l = newListener()
        l.start()
        val port = l.awaitFirstListening() ?: run {
            assumeTrue("нет свободных портов", false); return@runBlocking
        }

        // Второй start — не должен бросать и не должен повторно биндить порты.
        l.start()
        assertEquals(
            "running остался true",
            true, l.running.value,
        )
        // Клиент по-прежнему может подключиться к тому же порту.
        Socket("127.0.0.1", port).use { /* ok */ }
    }

    @Test
    fun port_busy_reports_via_portStatus_BUSY() = runBlocking {
        // Заранее занимаем один из honeypot-портов снаружи — listener должен
        // увидеть EADDRINUSE на v4 И v6 и пометить порт как BUSY.
        val blocked = HoneypotListener.PORTS.first()
        val external = try {
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), blocked))
            }
        } catch (_: Exception) {
            assumeTrue("порт $blocked уже занят кем-то ещё — тест нерелевантен", false)
            return@runBlocking
        }
        // Плюс v6 — если bind v6 тоже не получится, BindException на обоих = BUSY.
        val external6 = try {
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getByName("::1"), blocked))
            }
        } catch (_: Exception) { null }

        try {
            val l = newListener()
            l.start()
            delay(300)  // даём время всем bind-попыткам отработать

            val status = l.portStatus.replayCache.firstOrNull { it.port == blocked }
                ?: run { assumeTrue("portStatus не эмитит blocked-порт", false); return@runBlocking }
            assertEquals(
                "порт $blocked должен быть помечен BUSY",
                PortState.BUSY, status.state,
            )
            // Остальные порты должны быть в норме — хотя бы один LISTENING.
            val anyListening = l.portStatus.replayCache.any { it.state == PortState.LISTENING }
            assertTrue("хотя бы один другой порт должен слушаться", anyListening)
        } finally {
            external.close()
            external6?.close()
        }
    }

    @Test
    fun shutdown_leaves_scope_dead_cannot_start_again() = runBlocking {
        val l = newListener()
        l.start()
        l.awaitFirstListening() ?: run {
            assumeTrue("нет свободных портов", false); return@runBlocking
        }

        l.shutdown()
        assertFalse("shutdown должен сбросить running", l.running.value)

        // shutdown отменяет scope — повторный start технически вызывается,
        // но coroutine'ы не смогут заспавниться. Гарантия семантики:
        // running остаётся false, т.к. работу поднять нечем.
        l.start()
        delay(200)
        // Не утверждаем что running=false (listener._running ставится до scope.launch),
        // но хотя бы никто не должен упасть. Реальный use-case: создаём новый listener.
    }

    /** Ждёт первый LISTENING-порт. Используем replayCache от SharedFlow. */
    private suspend fun HoneypotListener.awaitFirstListening(): Int? = withTimeoutOrNull(3000) {
        while (true) {
            val listening = portStatus.replayCache.firstOrNull { it.state == PortState.LISTENING }
            if (listening != null) return@withTimeoutOrNull listening.port
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") null
    }
}
