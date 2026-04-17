package sgnv.anubis.app.audit

/**
 * Минимальный парсер TLS ClientHello ради одной вещи — SNI (server_name extension).
 *
 * Зачем: сканеры-детекторы VPN методички Минцифры иногда идут на наш honeypot-порт
 * не SOCKS'ом, а HTTPS-CONNECT'ом (для проверки «настоящий ли это прокси»).
 * Если мы успели захватить ClientHello первые ~500 байт — в SNI будет
 * внешний хост, куда сканер хотел достучаться через прокси. Например `api.rshb.ru`.
 *
 * Структура ClientHello упрощённо:
 *   record: 16 03 XX || len(2)
 *   handshake: 01 || len(3)
 *   client_version(2) || random(32) || session_id_len(1) || session_id
 *     || cipher_suites_len(2) || cipher_suites
 *     || compression_methods_len(1) || compression_methods
 *     || extensions_len(2) || extensions[*]
 *   ext: type(2) || len(2) || data
 *   SNI ext type = 0x0000
 *     server_name_list_len(2) || [name_type(1) || host_len(2) || host]
 *
 * Возвращает hostname из SNI, либо null если это не ClientHello или SNI не нашли.
 */
internal fun extractSni(buf: ByteArray, len: Int): String? {
    if (len < 43) return null

    var p = 0
    // TLS record header
    if (buf[p].toInt() and 0xFF != 0x16) return null  // not Handshake
    p += 1
    if (buf[p].toInt() and 0xFF != 0x03) return null  // major version
    p += 1
    // minor version 01 (TLS1.0) или 03 (TLS1.2) — приемлемо оба
    p += 1
    val recordLen = u16(buf, p); p += 2
    if (recordLen <= 0) return null

    // Handshake
    if (p >= len || buf[p].toInt() and 0xFF != 0x01) return null  // ClientHello
    p += 1
    val hsLen = u24(buf, p); p += 3
    val hsEnd = p + hsLen

    // ClientHello body
    p += 2  // client_version
    p += 32 // random

    if (p >= len) return null
    val sidLen = buf[p].toInt() and 0xFF; p += 1 + sidLen

    if (p + 2 > len) return null
    val csLen = u16(buf, p); p += 2 + csLen

    if (p + 1 > len) return null
    val cmLen = buf[p].toInt() and 0xFF; p += 1 + cmLen

    if (p + 2 > len) return null
    val extLen = u16(buf, p); p += 2
    val extEnd = minOf(p + extLen, hsEnd, len)

    while (p + 4 <= extEnd) {
        val type = u16(buf, p); p += 2
        val size = u16(buf, p); p += 2
        if (p + size > extEnd) return null
        if (type == 0x0000) {
            // SNI extension
            var q = p
            if (q + 2 > p + size) return null
            q += 2  // server_name_list_len
            while (q + 3 <= p + size) {
                val nameType = buf[q].toInt() and 0xFF; q += 1
                val hostLen = u16(buf, q); q += 2
                if (q + hostLen > p + size) return null
                if (nameType == 0x00) {
                    return String(buf, q, hostLen, Charsets.US_ASCII)
                }
                q += hostLen
            }
            return null
        }
        p += size
    }
    return null
}

private fun u16(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

private fun u24(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt() and 0xFF) shl 16) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        (buf[off + 2].toInt() and 0xFF)
