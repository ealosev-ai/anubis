package sgnv.anubis.app.audit.model

/**
 * Одно срабатывание honeypot-детектора: кто-то подключился к
 * нашему фейковому SOCKS/HTTP/Tor-прокси на localhost.
 *
 * @param timestampMs момент подключения
 * @param port номер порта, на котором ловили (1080/9000/5555/9050/3128/8080)
 * @param uid системный UID источника (из /proc/net/tcp), null если не удалось прочитать
 * @param packageName имя пакета, резолвленное через `pm list packages --uid`, null если не смогли
 * @param handshakePreview первые байты того, что приложение прислало (hex), для диагностики —
 *        у SOCKS5 это будет `05 01 00` (версия, 1 метод, no-auth), у HTTP-прокси `CONNECT ...`.
 */
data class AuditHit(
    val timestampMs: Long,
    val port: Int,
    val uid: Int?,
    val packageName: String?,
    val handshakePreview: String?,
    /**
     * Если клиент отправил TLS ClientHello (пытался проксировать HTTPS через нашу
     * ловушку) — здесь server_name из SNI extension: внешний хост, куда он метился.
     */
    val sni: String? = null,
    /** "TCP" или "UDP" — по какому протоколу шёл сканер. */
    val protocol: String = "TCP",
)

/** Агрегат для UI: по пакету/uid — сколько раз сканил и какие порты. */
data class AuditSuspect(
    val packageName: String?,
    val uid: Int?,
    val hitCount: Int,
    val portsSeen: Set<Int>,
    val lastSeenMs: Long,
    val lastHandshakePreview: String?,
    val sniSeen: Set<String> = emptySet(),
    val protocolsSeen: Set<String> = emptySet(),
) {
    /** Ключ для группировки: пакет, если известен, иначе uid, иначе фолбэк. */
    val groupKey: String = packageName ?: uid?.let { "uid:$it" } ?: "unknown"

    val displayName: String = packageName ?: uid?.let { "uid $it (не резолвлен)" } ?: "неизвестный"
}
