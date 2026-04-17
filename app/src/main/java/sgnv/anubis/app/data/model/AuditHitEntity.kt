package sgnv.anubis.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Персистентный хит honeypot'а. В AuditHit-DTO (audit/model/AuditHit.kt)
 * нет id, т.к. он живёт в памяти; здесь auto-increment PK нужен чтобы
 * у экспорта была стабильная упорядоченность и дедупликация при replay.
 */
@Entity(tableName = "audit_hits")
data class AuditHitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val port: Int,
    val uid: Int?,
    val packageName: String?,
    val handshakePreview: String?,
    /** SNI hostname из TLS ClientHello, если клиент прислал HTTPS-handshake. */
    val sni: String? = null,
    /** "TCP" или "UDP". Старые записи до v6 — всегда TCP. */
    val protocol: String = "TCP",
)
