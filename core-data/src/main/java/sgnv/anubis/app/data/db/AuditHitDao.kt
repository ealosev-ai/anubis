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
}
