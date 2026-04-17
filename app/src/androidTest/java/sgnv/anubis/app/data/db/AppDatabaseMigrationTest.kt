package sgnv.anubis.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room-миграции на реальном Android SQLite (эмулятор/устройство).
 *
 * Не используем MigrationTestHelper: он требует JSON-схему v3, а мы её
 * никогда не экспортировали (до форка Room сидел на fallbackToDestructiveMigration).
 * Вместо этого открываем raw SQLiteDatabase, создаём v3-схему точно как было
 * у автора, закрываем, потом открываем через Room — он сам прогонит миграции.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @Test
    fun migrates_from_v3_to_current_preserving_managed_apps() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "migration-test.db"
        val dbFile = ctx.getDatabasePath(dbName).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }

        // 1. Создаём v3-базу руками. Схема как была у автора до нас:
        //    managed_apps(packageName PK, group). Никаких audit_hits.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `managed_apps` (
                    `packageName` TEXT NOT NULL,
                    `group` TEXT NOT NULL,
                    PRIMARY KEY(`packageName`)
                )
                """.trimIndent()
            )
            raw.execSQL("INSERT INTO managed_apps (packageName, `group`) VALUES ('ru.rshb.dbo', 'LOCAL')")
            raw.execSQL("INSERT INTO managed_apps (packageName, `group`) VALUES ('com.ozon.app.android', 'VPN_ONLY')")
            raw.version = 3  // PRAGMA user_version — Room увидит это как «текущая версия БД 3»
        }

        // 2. Открываем через Room с нашими миграциями — оно поднимет v3 → v6.
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_3_4_EXPOSED, MIGRATION_4_5_EXPOSED, MIGRATION_5_6_EXPOSED)
            .build()
        try {
            db.openHelper.writableDatabase  // триггерит миграции

            // 3. managed_apps живы и без искажений
            db.openHelper.readableDatabase.query(
                "SELECT packageName, `group` FROM managed_apps ORDER BY packageName"
            ).use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals("com.ozon.app.android", cursor.getString(0))
                assertEquals("VPN_ONLY", cursor.getString(1))
                assertTrue(cursor.moveToNext())
                assertEquals("ru.rshb.dbo", cursor.getString(0))
                assertEquals("LOCAL", cursor.getString(1))
            }

            // 4. audit_hits создалась и имеет нужные колонки (sni, protocol)
            db.openHelper.readableDatabase.query("PRAGMA table_info(audit_hits)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
                assertTrue("ожидали колонку sni", "sni" in columns)
                assertTrue("ожидали колонку protocol", "protocol" in columns)
                assertTrue("ожидали id", "id" in columns)
                assertTrue("ожидали timestampMs", "timestampMs" in columns)
            }

            // 5. Default 'TCP' работает при INSERT без protocol
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO audit_hits (timestampMs, port, uid, packageName, handshakePreview) " +
                    "VALUES (1700000000, 1080, 10234, 'ru.test', '05 01 00')"
            )
            db.openHelper.readableDatabase.query(
                "SELECT protocol FROM audit_hits WHERE packageName = 'ru.test'"
            ).use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals("TCP", cursor.getString(0))
            }
        } finally {
            db.close()
            dbFile.delete()
        }
    }
}
