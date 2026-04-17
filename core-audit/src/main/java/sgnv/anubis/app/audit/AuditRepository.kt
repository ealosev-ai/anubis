package sgnv.anubis.app.audit

import org.json.JSONArray
import org.json.JSONObject
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.audit.model.AuditSuspect
import sgnv.anubis.app.data.db.AuditHitDao
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.AuditHitEntity
import sgnv.anubis.app.data.repository.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Агрегирует AuditHit в список AuditSuspect для UI, связывает «подозреваемых» с AppRepository
 * (кнопка «добавить в LOCAL») и персистит хиты в Room — так улики переживают убийство процесса.
 *
 * `hitLog` / `suspects` — это StateFlow'и, которые UI читает через collectAsState. Источник
 * истины — таблица `audit_hits`: при старте мы подписываемся на `dao.latest()` и перезаливаем
 * кэш при каждом insert.
 */
class AuditRepository(
    private val appRepository: AppRepository,
    private val dao: AuditHitDao,
    externalScope: CoroutineScope? = null,
) {
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _suspects = MutableStateFlow<List<AuditSuspect>>(emptyList())
    val suspects: StateFlow<List<AuditSuspect>> = _suspects

    private val _hitLog = MutableStateFlow<List<AuditHit>>(emptyList())
    val hitLog: StateFlow<List<AuditHit>> = _hitLog

    init {
        // Подписываемся на БД — при старте подхватываем вчерашние хиты, при insert
        // автоматически обновляемся. limit=1000 достаточно для UI; полный экспорт
        // идёт через exportAsJson() прямым SELECT'ом.
        scope.launch {
            dao.latest(limit = 1000).collect { entities ->
                val hits = entities.map { it.toHit() }
                _hitLog.value = hits
                _suspects.value = aggregate(hits)
            }
        }
    }

    suspend fun recordHit(hit: AuditHit) {
        dao.insert(hit.toEntity())
        // StateFlow'ы обновит подписка на dao.latest() — дублировать не надо.
    }

    /** Реактивный счётчик хитов с заданного момента — для HomeScreen-карточки. */
    fun countSinceFlow(sinceMs: Long): Flow<Int> = dao.countSinceFlow(sinceMs)

    /** Timestamp'ы всех хитов от заданного момента — для heat-map UI. */
    fun timestampsSinceFlow(sinceMs: Long): Flow<List<Long>> = dao.timestampsSinceFlow(sinceMs)

    suspend fun clear() {
        dao.clear()
        _hitLog.value = emptyList()
        _suspects.value = emptyList()
    }

    suspend fun markAsLocal(packageName: String) {
        appRepository.setAppGroup(packageName, AppGroup.LOCAL)
    }

    /**
     * Выгружает все хиты как JSON для ShareSheet. Формат массивом объектов —
     * чтобы легко грепать/парсить; порядок от старых к новым.
     */
    suspend fun exportAsJson(): String {
        val all = dao.getAllForExport()
        val arr = JSONArray()
        for (h in all) {
            arr.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("timestampMs", h.timestampMs)
                    put("port", h.port)
                    put("uid", h.uid ?: JSONObject.NULL)
                    put("packageName", h.packageName ?: JSONObject.NULL)
                    put("handshakePreview", h.handshakePreview ?: JSONObject.NULL)
                    put("sni", h.sni ?: JSONObject.NULL)
                    put("protocol", h.protocol)
                }
            )
        }
        return JSONObject().apply {
            put("exportedAtMs", System.currentTimeMillis())
            put("hitCount", all.size)
            put("hits", arr)
        }.toString(2)
    }

    private fun aggregate(hits: List<AuditHit>): List<AuditSuspect> {
        if (hits.isEmpty()) return emptyList()
        val groups = hits.groupBy { h -> h.packageName ?: h.uid?.let { "uid:$it" } ?: "unknown" }
        return groups.values
            .map { group ->
                val latest = group.maxBy { it.timestampMs }
                AuditSuspect(
                    packageName = group.firstNotNullOfOrNull { it.packageName },
                    uid = group.firstNotNullOfOrNull { it.uid },
                    hitCount = group.size,
                    portsSeen = group.map { it.port }.toSet(),
                    lastSeenMs = latest.timestampMs,
                    lastHandshakePreview = latest.handshakePreview,
                    sniSeen = group.mapNotNull { it.sni }.toSet(),
                    protocolsSeen = group.map { it.protocol }.toSet(),
                )
            }
            .sortedByDescending { it.lastSeenMs }
    }
}

private fun AuditHit.toEntity() = AuditHitEntity(
    timestampMs = timestampMs,
    port = port,
    uid = uid,
    packageName = packageName,
    handshakePreview = handshakePreview,
    sni = sni,
    protocol = protocol,
)

private fun AuditHitEntity.toHit() = AuditHit(
    timestampMs = timestampMs,
    port = port,
    uid = uid,
    packageName = packageName,
    handshakePreview = handshakePreview,
    sni = sni,
    protocol = protocol,
)
