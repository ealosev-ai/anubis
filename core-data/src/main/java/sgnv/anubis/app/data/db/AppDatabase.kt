package sgnv.anubis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.AuditHitEntity
import sgnv.anubis.app.data.model.ManagedApp

class AppGroupConverter {
    @TypeConverter
    fun fromAppGroup(group: AppGroup): String = group.name

    @TypeConverter
    fun toAppGroup(name: String): AppGroup = AppGroup.valueOf(name)
}

/**
 * Миграции вынесены top-level (а не в companion), чтобы androidTest мог их
 * гонять напрямую через AppDatabaseMigrationTest без reflection-штучек.
 */
val MIGRATION_3_4_EXPOSED = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `audit_hits` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `timestampMs` INTEGER NOT NULL,
                `port` INTEGER NOT NULL,
                `uid` INTEGER,
                `packageName` TEXT,
                `handshakePreview` TEXT
            )
            """.trimIndent()
        )
    }
}

/** v4 → v5: добавили SNI hostname из TLS ClientHello. */
val MIGRATION_4_5_EXPOSED = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `audit_hits` ADD COLUMN `sni` TEXT")
    }
}

/** v5 → v6: добавили протокол TCP/UDP (default TCP для старых записей). */
val MIGRATION_5_6_EXPOSED = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `audit_hits` ADD COLUMN `protocol` TEXT NOT NULL DEFAULT 'TCP'"
        )
    }
}

@Database(
    entities = [ManagedApp::class, AuditHitEntity::class],
    version = 6,
    exportSchema = false,
)
@TypeConverters(AppGroupConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun managedAppDao(): ManagedAppDao
    abstract fun auditHitDao(): AuditHitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vpn_stealth.db"
                )
                    .addMigrations(MIGRATION_3_4_EXPOSED, MIGRATION_4_5_EXPOSED, MIGRATION_5_6_EXPOSED)
                    // v1/v2 — исторические, схемы не известны. Fallback оставляем
                    // как safety net именно для них; для v3+ работают явные Migration.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
