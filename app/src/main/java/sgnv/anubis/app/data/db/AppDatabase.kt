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

        /**
         * v3 → v4: добавили таблицу audit_hits для персиста honeypot-журнала.
         * Раньше hits жили только в RAM — при убийстве процесса улики терялись.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
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
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audit_hits` ADD COLUMN `sni` TEXT")
            }
        }

        /** v5 → v6: добавили протокол TCP/UDP (default TCP для старых записей). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `audit_hits` ADD COLUMN `protocol` TEXT NOT NULL DEFAULT 'TCP'"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vpn_stealth.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // v1/v2 — исторические, схемы не известны. Fallback оставляем
                    // как safety net именно для них; для v3+ работают явные Migration.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
