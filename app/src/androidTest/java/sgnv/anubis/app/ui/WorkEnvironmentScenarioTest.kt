package sgnv.anubis.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.fakeVpn
import sgnv.anubis.app.service.StealthState
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType

/**
 * E2E: "Рабочее окружение" — одна кнопка разворачивает весь VPN-контур.
 *
 * enableWorkEnvironment:
 *   freeze LOCAL → start VPN (FakeVpn.startVPN выставляет vpnActive=true) →
 *   waitForVpnOn → unfreeze VPN_ONLY/LAUNCH_VPN → launchApp каждое
 *
 * disableWorkEnvironment:
 *   freeze VPN_ONLY/LAUNCH_VPN → stop VPN → unfreeze LOCAL
 */
@RunWith(AndroidJUnit4::class)
class WorkEnvironmentScenarioTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // "банк" — реальный установленный пакет, реально замораживаем/размораживаем.
    private val localPkg = "moe.shizuku.privileged.api"
    // "Telegram" — тоже реальный пакет (deskclock есть на AVD). Важно чтобы он
    // был НЕ нашим собственным (заморозка своего процесса → crash) и не был
    // критическим — deskclock freeze'ится/unfreeze'ится без последствий.
    private val vpnPkg = "com.google.android.deskclock"

    private val app: AnubisApp by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AnubisApp
    }

    @Before
    fun setUp() = runBlocking {
        assumeTrue(
            "Shizuku не настроен — scripts/setup-avd-shizuku.sh",
            app.shizukuManager.awaitShizukuReady(5_000L),
        )
        app.appRepository.setAppGroup(localPkg, AppGroup.LOCAL)
        app.appRepository.setAppGroup(vpnPkg, AppGroup.VPN_ONLY)
        // Базовое состояние: VPN off, ничего не заморожено
        app.fakeVpn.setVpnActive(false)
        for (pkg in listOf(localPkg, vpnPkg)) {
            if (app.shizukuManager.isAppFrozen(pkg)) app.shizukuManager.unfreezeApp(pkg)
        }
    }

    @After
    fun tearDown() = runBlocking {
        app.appRepository.removeApp(localPkg)
        app.appRepository.removeApp(vpnPkg)
        for (pkg in listOf(localPkg, vpnPkg)) {
            if (app.shizukuManager.isAppFrozen(pkg)) app.shizukuManager.unfreezeApp(pkg)
        }
        app.fakeVpn.setVpnActive(false)
    }

    @Test
    fun enable_work_env_freezes_LOCAL_starts_vpn_unfreezes_and_launches_VPN_apps() = runBlocking {
        val client = SelectedVpnClient.fromKnown(VpnClientType.NEKO_BOX)
        app.orchestrator.enableWorkEnvironment(client)

        assertEquals(StealthState.ENABLED, app.orchestrator.state.value)
        assertEquals("VPN должен быть активен (FakeVpn.startVPN → vpnActive=true)",
            true, app.fakeVpn.vpnActive.value)
        // LOCAL-банк заморожен
        assertTrue("LOCAL pkg должен быть заморожен",
            app.shizukuManager.isAppFrozen(localPkg))
        // VPN-app разморожен — не трогаем launchApp проверку (FakeVpn.launchApp — no-op)
        assertFalse("VPN pkg должен быть разморожен",
            app.shizukuManager.isAppFrozen(vpnPkg))
    }

    @Test
    fun disable_work_env_freezes_vpn_apps_then_unfreezes_LOCAL() = runBlocking {
        val client = SelectedVpnClient.fromKnown(VpnClientType.NEKO_BOX)
        // Сначала поднять окружение
        app.orchestrator.enableWorkEnvironment(client)
        assertEquals(StealthState.ENABLED, app.orchestrator.state.value)

        // Симулируем что stopVPN кладёт VPN (Fake делает это сам; при refresh)
        app.fakeVpn.setVpnActive(true)  // уже true после enable

        app.orchestrator.disableWorkEnvironment(client, detectedPackage = null)

        assertEquals(StealthState.DISABLED, app.orchestrator.state.value)
        assertEquals(false, app.fakeVpn.vpnActive.value)
        // VPN pkg снова заморожен
        assertTrue("VPN pkg должен быть заморожен после disable",
            app.shizukuManager.isAppFrozen(vpnPkg))
        // LOCAL pkg разморожен
        assertFalse("LOCAL pkg должен быть разморожен после disable",
            app.shizukuManager.isAppFrozen(localPkg))
    }
}
