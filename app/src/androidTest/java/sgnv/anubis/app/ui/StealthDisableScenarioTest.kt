package sgnv.anubis.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * E2E: пользователь ВЫКЛЮЧАЕТ «ЗАЩИТА». Проверяем что:
 *   - StealthOrchestrator.disable() проходит 3-phase stop (в нашем fake —
 *     stopVPN сразу ставит vpnActive=false, fallback decoy/force-stop не нужен)
 *   - VPN_ONLY группа замораживается
 *   - state переходит в DISABLED
 */
@RunWith(AndroidJUnit4::class)
class StealthDisableScenarioTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val vpnOnlyPkg = "moe.shizuku.privileged.api"  // известный установленный на AVD

    private val app: AnubisApp by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AnubisApp
    }

    @Before
    fun setUp() = runBlocking {
        assumeTrue(
            "Shizuku не настроен — scripts/setup-avd-shizuku.sh",
            app.shizukuManager.awaitShizukuReady(5_000L),
        )
        // Стартовое состояние: VPN уже включён, VPN_ONLY pkg в группе, state=ENABLED.
        app.appRepository.setAppGroup(vpnOnlyPkg, AppGroup.VPN_ONLY)
        app.fakeVpn.setVpnActive(true)
        // Размораживаем pkg чтобы disable() реально его заморозил.
        if (app.shizukuManager.isAppFrozen(vpnOnlyPkg)) {
            app.shizukuManager.unfreezeApp(vpnOnlyPkg)
        }
        app.orchestrator.syncState()  // state=ENABLED (по vpnActive)
    }

    @After
    fun tearDown() = runBlocking {
        app.appRepository.removeApp(vpnOnlyPkg)
        if (app.shizukuManager.isAppFrozen(vpnOnlyPkg)) {
            app.shizukuManager.unfreezeApp(vpnOnlyPkg)
        }
        app.fakeVpn.setVpnActive(false)
    }

    @Test
    fun disable_transitions_to_DISABLED_and_freezes_VPN_ONLY_group() = runBlocking {
        assertEquals(StealthState.ENABLED, app.orchestrator.state.value)

        app.orchestrator.disable(
            client = SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG),
            detectedPackage = null,
        )

        assertEquals(StealthState.DISABLED, app.orchestrator.state.value)
        assertEquals(false, app.fakeVpn.vpnActive.value)
        assertTrue(
            "VPN_ONLY pkg должен быть заморожен после disable()",
            app.shizukuManager.isAppFrozen(vpnOnlyPkg),
        )
    }
}
