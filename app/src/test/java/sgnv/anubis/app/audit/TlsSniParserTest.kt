package sgnv.anubis.app.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Минимально валидный ClientHello с SNI собираем руками: Python/OpenSSL дампы
 * завязывают тест на внешние файлы, а парсер мы хотим пинать на edge-кейсах.
 */
class TlsSniParserTest {

    @Test
    fun parses_sni_from_standard_client_hello() {
        val buf = clientHelloWithSni("api.rshb.ru")
        val sni = extractSni(buf, buf.size)
        assertEquals("api.rshb.ru", sni)
    }

    @Test
    fun parses_sni_from_long_hostname() {
        val host = "very-long-backend-hostname.example.bank.ru"
        val buf = clientHelloWithSni(host)
        assertEquals(host, extractSni(buf, buf.size))
    }

    @Test
    fun returns_null_when_not_tls_handshake() {
        // SOCKS5 greeting: 05 01 00 ...
        val buf = byteArrayOf(0x05, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertNull(extractSni(buf, buf.size))
    }

    @Test
    fun returns_null_on_short_buffer() {
        val buf = byteArrayOf(0x16, 0x03, 0x01)
        assertNull(extractSni(buf, buf.size))
    }

    @Test
    fun returns_null_on_client_hello_without_sni_extension() {
        val buf = clientHelloNoExtensions()
        assertNull(extractSni(buf, buf.size))
    }

    @Test
    fun returns_null_on_garbage() {
        val buf = ByteArray(64) { 0xFF.toByte() }
        assertNull(extractSni(buf, buf.size))
    }

    @Test
    fun respects_len_parameter_over_array_size() {
        // Буфер больше чем фактическая длина handshake — парсер не должен выйти за len.
        val real = clientHelloWithSni("example.com")
        val padded = real.copyOf(real.size + 128)
        assertEquals("example.com", extractSni(padded, real.size))
    }

    // --- helpers ---

    /**
     * Собирает байт-в-байт валидный TLS 1.2 ClientHello с одним SNI extension.
     * Записи: record(5) + handshake(4) + body. Размеры считаем обратным ходом.
     */
    private fun clientHelloWithSni(hostname: String): ByteArray {
        val hostBytes = hostname.toByteArray(Charsets.US_ASCII)

        // SNI extension payload:
        //   server_name_list_length(2) || name_type(1) || host_len(2) || host
        val sniData = ByteArrayOutputStream().apply {
            writeU16(hostBytes.size + 3)  // list_len = 1 + 2 + host_len
            write(0x00)                     // name_type = hostname
            writeU16(hostBytes.size)
            write(hostBytes)
        }.toByteArray()

        // Single extension: type(2) + len(2) + data
        val extensions = ByteArrayOutputStream().apply {
            writeU16(0x0000)                // SNI ext type
            writeU16(sniData.size)
            write(sniData)
        }.toByteArray()

        // ClientHello body: version(2) + random(32) + sid_len(1) +
        //                   cipher_suites_len(2) + cipher(2) + cm_len(1) + cm(1) +
        //                   ext_len(2) + extensions
        val body = ByteArrayOutputStream().apply {
            writeU16(0x0303)                // TLS 1.2 client_version
            write(ByteArray(32))            // random
            write(0x00)                     // session_id_len = 0
            writeU16(2); writeU16(0x0035)   // cipher_suites len=2 + TLS_RSA_WITH_AES_256_CBC_SHA
            write(1); write(0x00)           // compression_methods len=1 + NULL
            writeU16(extensions.size)
            write(extensions)
        }.toByteArray()

        // Handshake: type(1) + len(3) + body
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)                     // ClientHello
            writeU24(body.size)
            write(body)
        }.toByteArray()

        // TLS record: type(1) + version(2) + len(2) + handshake
        return ByteArrayOutputStream().apply {
            write(0x16)                     // Handshake
            writeU16(0x0301)                // TLS 1.0 record version (клиенты так делают)
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()
    }

    private fun clientHelloNoExtensions(): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeU16(0x0303)
            write(ByteArray(32))
            write(0x00)
            writeU16(2); writeU16(0x0035)
            write(1); write(0x00)
            writeU16(0)                     // ext_len = 0 → вообще нет extensions
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01)
            writeU24(body.size)
            write(body)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x16)
            writeU16(0x0301)
            writeU16(handshake.size)
            write(handshake)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU24(v: Int) {
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }
}
