package sgnv.anubis.app.audit

import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.audit.model.AuditSuspect
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Агрегирует AuditHit в список AuditSuspect для UI и связывает «подозреваемых» с AppRepository
 * (кнопка «добавить в LOCAL» на экране аудита).
 *
 * Данные эфемерные — живут только в памяти, `clear()` обнуляет. Ничего не пишем в Room.
 */
class AuditRepository(
    private val appRepository: AppRepository,
) {
    private val _suspects = MutableStateFlow<List<AuditSuspect>>(emptyList())
    val suspects: StateFlow<List<AuditSuspect>> = _suspects

    /** Плоский журнал, для диагностики/отладки UI. */
    private val _hitLog = MutableStateFlow<List<AuditHit>>(emptyList())
    val hitLog: StateFlow<List<AuditHit>> = _hitLog

    @Synchronized
    fun recordHit(hit: AuditHit) {
        _hitLog.value = (_hitLog.value + hit).takeLast(500)
        _suspects.value = aggregate(_hitLog.value)
    }

    fun clear() {
        _hitLog.value = emptyList()
        _suspects.value = emptyList()
    }

    suspend fun markAsLocal(packageName: String) {
        appRepository.setAppGroup(packageName, AppGroup.LOCAL)
    }

    private fun aggregate(hits: List<AuditHit>): List<AuditSuspect> {
        if (hits.isEmpty()) return emptyList()
        // Группируем по pkg/uid: один и тот же сканер может светиться с одним uid,
        // а pm list не всегда резолвит (например, для системных компонентов).
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
                )
            }
            .sortedByDescending { it.lastSeenMs }
    }
}
