package sgnv.anubis.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.GroupsBackup

/**
 * E2E: backup→restore roundtrip через реальный AppRepository (= Room) +
 * GroupsBackup JSON. Проверяет что замороженный pkg в LOCAL переживает
 * export→clear→import.
 */
@RunWith(AndroidJUnit4::class)
class GroupsBackupScenarioTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: AnubisApp by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AnubisApp
    }

    private val pkg1 = "ru.anubis.test.bank1"
    private val pkg2 = "ru.anubis.test.bank2"

    @Before
    fun setUp() = runBlocking {
        app.appRepository.setAppGroup(pkg1, AppGroup.LOCAL)
        app.appRepository.setAppGroup(pkg2, AppGroup.VPN_ONLY)
    }

    @After
    fun tearDown() = runBlocking {
        app.appRepository.removeApp(pkg1)
        app.appRepository.removeApp(pkg2)
    }

    @Test
    fun export_then_clear_then_import_restores_groups() = runBlocking {
        // Export через настоящий AppRepository → JSON.
        val json = GroupsBackup.export(app.appRepository)
        assertTrue("JSON должен содержать pkg1", json.contains(pkg1))
        assertTrue("JSON должен содержать LOCAL", json.contains("\"LOCAL\""))

        // Очищаем — pkg1/pkg2 больше не в группах.
        app.appRepository.removeApp(pkg1)
        app.appRepository.removeApp(pkg2)
        assertEquals(null, app.appRepository.getAppGroup(pkg1))
        assertEquals(null, app.appRepository.getAppGroup(pkg2))

        // Restore через import — группы должны вернуться.
        val n = GroupsBackup.import(app.appRepository, json)
        assertEquals(2, n)
        assertEquals(AppGroup.LOCAL, app.appRepository.getAppGroup(pkg1))
        assertEquals(AppGroup.VPN_ONLY, app.appRepository.getAppGroup(pkg2))
    }

    @Test
    fun replaceAll_wipes_unrelated_entries() = runBlocking {
        val unrelatedPkg = "ru.anubis.test.unrelated"
        app.appRepository.setAppGroup(unrelatedPkg, AppGroup.LOCAL)

        val json = """
            {"version":1,"apps":[
              {"packageName":"$pkg1","group":"LAUNCH_VPN"}
            ]}
        """.trimIndent()

        val n = GroupsBackup.replaceAll(app.appRepository, json)
        assertEquals(1, n)
        // pkg1 переписан
        assertEquals(AppGroup.LAUNCH_VPN, app.appRepository.getAppGroup(pkg1))
        // unrelated — стёрт (не в бекапе)
        assertFalse(
            "unrelated должен быть удалён при replaceAll",
            app.appRepository.getAppGroup(unrelatedPkg) != null,
        )
        // pkg2 — стёрт (не в бекапе)
        assertFalse(app.appRepository.getAppGroup(pkg2) != null)

        // Cleanup
        app.appRepository.removeApp(unrelatedPkg)
    }
}
