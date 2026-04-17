package sgnv.anubis.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Честный тест Room-миграций на настоящем Android (эмулятор / устройство).
 *
 * Создаём базу v3 руками (SQL из реальной схемы автора), кладём туда managed_apps,
 * прогоняем MIGRATION_3_4 / _4_5 / _5_6 и проверяем что:
 *   - старые строки managed_apps живы
 *   - audit_hits создана и имеет правильные колонки (sni, protocol)
 *   - default для protocol = 'TCP' применился к (потенциально) старым строкам
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrates_from_v3_to_current_preserving_managed_apps() {
        // v3: одна таблица managed_apps(packageName PK, group). Создаём руками,
        // потому что JSON-схемы v3 никогда не экспортировали (fallback был).
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `managed_apps` (
                    `packageName` TEXT NOT NULL,
                    `group` TEXT NOT NULL,
                    PRIMARY KEY(`packageName`)
                )
                """.trimIndent()
            )
            execSQL("INSERT INTO managed_apps (packageName, `group`) VALUES ('ru.rshb.dbo', 'LOCAL')")
            execSQL("INSERT INTO managed_apps (packageName, `group`) VALUES ('com.ozon.app.android', 'VPN_ONLY')")
            close()
        }

        // Прогоняем все миграции через реальный Room builder — он откроет базу,
        // применит MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6 одним махом.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(
                MIGRATION_3_4_EXPOSED,
                MIGRATION_4_5_EXPOSED,
                MIGRATION_5_6_EXPOSED,
            )
            .build()
        db.openHelper.writableDatabase  // триггерит миграции

        // 1. managed_apps не потерялись
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

        // 2. audit_hits создана и содержит колонки sni + protocol (они появились в v5/v6)
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

        // 3. default для protocol действительно 'TCP' — insert без protocol
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

        db.close()
    }
}
