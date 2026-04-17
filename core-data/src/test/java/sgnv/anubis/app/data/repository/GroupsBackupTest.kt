package sgnv.anubis.app.data.repository

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.model.ManagedApp

class GroupsBackupTest {

    @Test
    fun export_serializes_all_groups_round_trippable() = runTest {
        val store = FakeGroupsStore(
            mapOf(
                "ru.rshb.dbo" to AppGroup.LOCAL,
                "com.ozon.app.android" to AppGroup.VPN_ONLY,
                "com.telegram" to AppGroup.LAUNCH_VPN,
            )
        )
        val json = GroupsBackup.export(store)

        val root = JSONObject(json)
        assertEquals(1, root.getInt("version"))
        assertEquals(3, root.getInt("count"))
        val arr = root.getJSONArray("apps")
        val seen = buildMap<String, String> {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.getString("packageName"), o.getString("group"))
            }
        }
        assertEquals("LOCAL", seen["ru.rshb.dbo"])
        assertEquals("VPN_ONLY", seen["com.ozon.app.android"])
        assertEquals("LAUNCH_VPN", seen["com.telegram"])
    }

    @Test
    fun import_merges_into_empty_store() = runTest {
        val store = FakeGroupsStore(emptyMap())
        val json = """
            {"version":1,"apps":[
              {"packageName":"ru.rshb.dbo","group":"LOCAL"},
              {"packageName":"com.telegram","group":"LAUNCH_VPN"}
            ]}
        """.trimIndent()

        val n = GroupsBackup.import(store, json)
        assertEquals(2, n)
        assertEquals(AppGroup.LOCAL, store.getGroupOrNull("ru.rshb.dbo"))
        assertEquals(AppGroup.LAUNCH_VPN, store.getGroupOrNull("com.telegram"))
    }

    @Test
    fun import_merge_keeps_existing_entries_not_in_backup() = runTest {
        val store = FakeGroupsStore(
            mapOf(
                "com.manual.entry" to AppGroup.LOCAL,
                "ru.rshb.dbo" to AppGroup.VPN_ONLY,  // потом будет перезаписано
            )
        )
        val json = """
            {"apps":[{"packageName":"ru.rshb.dbo","group":"LOCAL"}]}
        """.trimIndent()

        val n = GroupsBackup.import(store, json)
        assertEquals(1, n)
        // ручная запись не потеряна
        assertEquals(AppGroup.LOCAL, store.getGroupOrNull("com.manual.entry"))
        // совпавшая — перезаписана
        assertEquals(AppGroup.LOCAL, store.getGroupOrNull("ru.rshb.dbo"))
    }

    @Test
    fun replaceAll_wipes_and_applies() = runTest {
        val store = FakeGroupsStore(
            mapOf(
                "com.manual.entry" to AppGroup.LOCAL,
                "ru.rshb.dbo" to AppGroup.VPN_ONLY,
            )
        )
        val json = """
            {"apps":[{"packageName":"com.telegram","group":"LAUNCH_VPN"}]}
        """.trimIndent()

        val n = GroupsBackup.replaceAll(store, json)
        assertEquals(1, n)
        assertFalse(store.exists("com.manual.entry"))
        assertFalse(store.exists("ru.rshb.dbo"))
        assertEquals(AppGroup.LAUNCH_VPN, store.getGroupOrNull("com.telegram"))
    }

    @Test
    fun import_returns_minus_one_on_garbage_json() = runTest {
        val store = FakeGroupsStore(emptyMap())
        assertEquals(-1, GroupsBackup.import(store, "{not json"))
        assertEquals(-1, GroupsBackup.import(store, "[]"))  // нет поля apps
    }

    @Test
    fun import_skips_invalid_entries_but_imports_valid() = runTest {
        val store = FakeGroupsStore(emptyMap())
        val json = """
            {"apps":[
              {"packageName":"ru.valid","group":"LOCAL"},
              {"packageName":"","group":"LOCAL"},
              {"packageName":"ru.valid2","group":"UNKNOWN_GROUP"},
              {"packageName":"ru.valid3","group":"VPN_ONLY"}
            ]}
        """.trimIndent()
        val n = GroupsBackup.import(store, json)
        assertEquals("импортируем только валидные 2 из 4", 2, n)
        assertTrue(store.exists("ru.valid"))
        assertTrue(store.exists("ru.valid3"))
        assertFalse(store.exists("ru.valid2"))
    }

    /** Простая in-memory реализация [GroupsStore] для тестов. */
    private class FakeGroupsStore(initial: Map<String, AppGroup>) : GroupsStore {
        private val map = initial.toMutableMap()

        override suspend fun getAppsByGroup(group: AppGroup): List<ManagedApp> =
            map.filter { it.value == group }.map { ManagedApp(it.key, it.value) }

        override suspend fun setAppGroup(packageName: String, group: AppGroup) {
            map[packageName] = group
        }

        override suspend fun removeApp(packageName: String) {
            map.remove(packageName)
        }

        fun getGroupOrNull(pkg: String): AppGroup? = map[pkg]
        fun exists(pkg: String): Boolean = map.containsKey(pkg)
    }
}
