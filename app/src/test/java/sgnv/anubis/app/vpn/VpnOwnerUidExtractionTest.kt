package sgnv.anubis.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * extractVpnOwnerUid — top-level чистая функция. Тест покрывает три формата
 * dumpsys connectivity (A11/A12/A14) и отсев системных uid.
 */
class VpnOwnerUidExtractionTest {

    @Test
    fun android_11_style_type_vpn_square_bracket() {
        val dump = """
            NetworkAgentInfo{ netId 100  type: VPN[sgnv.anubis.app]
              OwnerUid: 10234
              Transports: VPN
              active request
            }
        """.trimIndent()
        assertEquals(10234, extractVpnOwnerUid(dump))
    }

    @Test
    fun android_12_style_transports_vpn() {
        val dump = """
            NetworkAgentInfo{ netId 101 }
              Transports: VPN
              LinkProperties ...
              OwnerUid: 10456

            NetworkAgentInfo{ netId 102 }
              Transports: WIFI
              OwnerUid: 10789
        """.trimIndent()
        assertEquals(10456, extractVpnOwnerUid(dump))
    }

    @Test
    fun android_14_style_bracketed_vpn() {
        val dump = """
            NetworkAgentInfo{ netId 200 [VPN] }
              OwnerUid: 10100
              Type: VPN
        """.trimIndent()
        assertEquals(10100, extractVpnOwnerUid(dump))
    }

    @Test
    fun skips_system_uids() {
        val dump = """
            NetworkAgentInfo{ type: VPN[system] }
              OwnerUid: 1000
        """.trimIndent()
        assertNull(extractVpnOwnerUid(dump))
    }

    @Test
    fun returns_null_when_no_vpn_network() {
        val dump = """
            NetworkAgentInfo{ netId 1 }
              Transports: WIFI
              OwnerUid: 10001
        """.trimIndent()
        assertNull(extractVpnOwnerUid(dump))
    }

    @Test
    fun picks_first_app_uid_when_multiple_vpn_entries() {
        val dump = """
            NetworkAgentInfo{ netId 1 type: VPN[a] }
              OwnerUid: 10100

            NetworkAgentInfo{ netId 2 type: VPN[b] }
              OwnerUid: 10200
        """.trimIndent()
        assertEquals(10100, extractVpnOwnerUid(dump))
    }

    @Test
    fun skips_not_vpn_label() {
        val dump = """
            NetworkAgentInfo{ NOT_VPN Transports: WIFI }
              OwnerUid: 10001
        """.trimIndent()
        // NOT_VPN содержит слово VPN — marker-regex должен его не матчить
        // (Transports: VPN — граница слов, тогда как 'NOT_VPN' не матчит
        // \bVPN\b благодаря подчёркиванию-как-wordchar). Проверим:
        assertNull(extractVpnOwnerUid(dump))
    }
}
