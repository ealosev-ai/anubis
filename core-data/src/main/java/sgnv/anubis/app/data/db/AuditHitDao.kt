package sgnv.anubis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sgnv.anubis.app.data.model.AuditHitEntity

@Dao
interface AuditHitDao {

    @Insert
    suspend fun insert(hit: AuditHitEntity): Long

    @Query("SELECT * FROM audit_hits ORDER BY timestampMs DESC LIMIT :limit")
    fun latest(limit: Int = 1000): Flow<List<AuditHitEntity>>

    @Query("SELECT * FROM audit_hits ORDER BY timestampMs ASC")
    suspend fun getAllForExport(): List<AuditHitEntity>

    @Query("DELETE FROM audit_hits")
    suspend fun clear()

    @Query("DELETE FROM audit_hits WHERE timestampMs < :olderThan")
    suspend fun purgeOlderThan(olderThan: Long)

    @Query("SELECT COUNT(*) FROM audit_hits WHERE timestampMs >= :sinceMs")
    fun countSinceFlow(sinceMs: Long): Flow<Int>

    /** Все timestamp'ы хитов за период — для heat-map. Reactive, dao emits on insert. */
    @Query("SELECT timestampMs FROM audit_hits WHERE timestampMs >= :sinceMs ORDER BY timestampMs")
    fun timestampsSinceFlow(sinceMs: Long): Flow<List<Long>>
}
