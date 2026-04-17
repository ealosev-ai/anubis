package sgnv.anubis.app.audit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UidResolverTest {

    /**
     * Честный фрагмент /proc/net/tcp с двумя симметричными сокетами на
     * loopback: наш server-side (uid = ownerUid приложения-форка) и
     * клиентский (uid = 10234, имитируем РСХБ). honeypotPort 0x0438 = 1080,
     * srcPort 0xC1A7 = эфемерный клиентский.
     */
    @Test
    fun resolves_tcp_client_uid_on_loopback_v4() = runTest {
        val shell = FakeShellExec()
        shell.on("cat /proc/net/tcp", """
            sl  local_address rem_address   st tx_queue rx_queue tr tm_when retrnsmt   uid
             0: 0100007F:0438 0100007F:C1A7 01 00000000:00000000 00:00000000 00000000 99999
             1: 0100007F:C1A7 0100007F:0438 01 00000000:00000000 00:00000000 00000000 10234
        """.trimIndent())
        shell.on("cat /proc/net/tcp6", "")
        shell.on("pm list packages --uid 10234", "package:ru.rshb.dbo")

        val resolver = UidResolver(shell)
        val (uid, pkg) = resolver.resolve(remotePort = 0xC1A7, localHoneypotPort = 1080)

        assertEquals(10234, uid)
        assertEquals("ru.rshb.dbo", pkg)
    }

    @Test
    fun returns_null_when_proc_net_empty() = runTest {
        val shell = FakeShellExec()
        shell.on("cat /proc/net/tcp", "")
        shell.on("cat /proc/net/tcp6", "")
        val resolver = UidResolver(shell)
        val (uid, pkg) = resolver.resolve(remotePort = 12345, localHoneypotPort = 1080)
        assertNull(uid)
        assertNull(pkg)
    }

    @Test
    fun ignores_unrelated_tcp_rows() = runTest {
        val shell = FakeShellExec()
        shell.on("cat /proc/net/tcp", """
            sl  local_address rem_address   st tx_queue rx_queue tr tm_when retrnsmt   uid
             0: 0100007F:0050 0100007F:DEAD 01 00000000:00000000 00:00000000 00000000 10500
             1: 0100007F:AAAA 0100007F:BBBB 01 00000000:00000000 00:00000000 00000000 10600
        """.trimIndent())
        shell.on("cat /proc/net/tcp6", "")
        val resolver = UidResolver(shell)
        val (uid, _) = resolver.resolve(remotePort = 0xC1A7, localHoneypotPort = 1080)
        assertNull(uid)  // подходящей пары нет
    }

    @Test
    fun caches_pm_list_packages_call() = runTest {
        val shell = FakeShellExec()
        shell.on("cat /proc/net/tcp", """
            sl  local_address rem_address   st tx_queue rx_queue tr tm_when retrnsmt   uid
             0: 0100007F:C1A7 0100007F:0438 01 00000000:00000000 00:00000000 00000000 10234
        """.trimIndent())
        shell.on("cat /proc/net/tcp6", "")
        shell.on("pm list packages --uid 10234", "package:ru.rshb.dbo")

        val resolver = UidResolver(shell)
        repeat(3) {
            resolver.resolve(remotePort = 0xC1A7, localHoneypotPort = 1080)
        }
        val pmCalls = shell.calls.count { it.firstOrNull() == "pm" }
        assertEquals("pm list должен быть вызван один раз и закэширован", 1, pmCalls)
    }

    @Test
    fun udp_resolver_skips_own_uid() = runTest {
        // Наш server-side UDP socket — uid процесса теста (Process.myUid()).
        // В тесте он не определён, но UidResolver вызывает Process.myUid(),
        // который под JVM unit-тестом с returnDefaultValues=true вернёт 0.
        // Значит строка с uid=0 должна быть пропущена.
        val shell = FakeShellExec()
        shell.on("cat /proc/net/udp", """
            sl  local_address rem_address   st tx_queue rx_queue tr tm_when retrnsmt   uid
             0: 0100007F:1234 00000000:0000 07 00000000:00000000 00:00000000 00000000 0
             1: 0100007F:1234 00000000:0000 07 00000000:00000000 00:00000000 00000000 10777
        """.trimIndent())
        shell.on("cat /proc/net/udp6", "")
        shell.on("pm list packages --uid 10777", "package:com.bank.app")

        val resolver = UidResolver(shell)
        val (uid, pkg) = resolver.resolveUdp(srcPort = 0x1234)
        assertEquals(10777, uid)
        assertEquals("com.bank.app", pkg)
    }
}
