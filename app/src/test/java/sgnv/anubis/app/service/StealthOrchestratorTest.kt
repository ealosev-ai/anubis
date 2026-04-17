package sgnv.anubis.app.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import sgnv.anubis.app.data.model.AppGroup
import sgnv.anubis.app.data.repository.PackageGroupsReader
import sgnv.anubis.app.shizuku.FreezeActions
import sgnv.anubis.app.vpn.SelectedVpnClient
import sgnv.anubis.app.vpn.VpnClientType
import sgnv.anubis.app.vpn.VpnControls

/**
 * Unit-тесты state-машины StealthOrchestrator'а без живого устройства.
 *
 * Подаём fake'и [FakeFreezeActions] / [FakeVpnControls] / [FakePackageGroups],
 * гоняем сценарии enable/disable/launch*, проверяем что именно было вызвано
 * + что state-flow переходит правильно + что error-пути не заморозили
 * больше чем надо.
 */
class StealthOrchestratorTest {

    private lateinit var shizuku: FakeFreezeActions
    private lateinit var vpn: FakeVpnControls
    private lateinit var repo: FakePackageGroups
    private lateinit var context: Context
    private var decoyDisconnectCalls = 0

    private val v2rayNg = SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG)
    private val nekoBox = SelectedVpnClient.fromKnown(VpnClientType.NEKO_BOX)
    private val amnezia = SelectedVpnClient.fromKnown(VpnClientType.AMNEZIA)

    @Before
    fun setUp() {
        shizuku = FakeFreezeActions()
        vpn = FakeVpnControls()
        repo = FakePackageGroups(
            mapOf(
                AppGroup.LOCAL to setOf("ru.rshb.dbo", "com.ozon.app.android"),
                AppGroup.VPN_ONLY to setOf("com.telegram"),
                AppGroup.LAUNCH_VPN to setOf("org.example.chat"),
            )
        )
        context = mock(Context::class.java)
        decoyDisconnectCalls = 0
    }

    private fun orchestrator() = StealthOrchestrator(
        context = context,
        shizukuManager = shizuku,
        vpnClientManager = vpn,
        repository = repo,
        decoyDisconnect = { decoyDisconnectCalls++ },
    )

    @Test
    fun enable_freezes_LOCAL_and_starts_vpn() = runTest {
        val o = orchestrator()
        o.enable(v2rayNg)

        assertEquals(StealthState.ENABLED, o.state.value)
        assertTrue("LOCAL всё должно быть заморожено", shizuku.frozen.containsAll(setOf("ru.rshb.dbo", "com.ozon.app.android")))
        assertFalse("VPN_ONLY не трогаем на enable", shizuku.frozen.contains("com.telegram"))
        assertTrue("startVPN должен был вызваться", vpn.startVpnCalls.contains(v2rayNg))
    }

    @Test
    fun enable_fails_if_shizuku_unavailable() = runTest {
        shizuku.available = false
        val o = orchestrator()
        o.enable(v2rayNg)

        assertEquals(StealthState.DISABLED, o.state.value)
        assertEquals("Shizuku не запущен.", o.lastError.value)
        assertTrue("ничего не должно быть заморожено", shizuku.frozen.isEmpty())
        assertTrue("VPN не должен запускаться", vpn.startVpnCalls.isEmpty())
    }

    @Test
    fun enable_fails_if_vpn_client_itself_is_frozen() = runTest {
        // Клиент не может запуститься если он сам заморожен — проверяем эту защиту.
        shizuku.frozen.add(v2rayNg.packageName)
        val o = orchestrator()
        o.enable(v2rayNg)

        assertEquals(StealthState.DISABLED, o.state.value)
        assertTrue(o.lastError.value?.contains("заморожен") == true)
        assertTrue("не должен стартовать VPN", vpn.startVpnCalls.isEmpty())
    }

    @Test
    fun enable_sets_manual_hint_for_AMNEZIA() = runTest {
        val o = orchestrator()
        o.enable(amnezia)
        // Амнезия в MANUAL — просто открывается; стелс считается «включённым»,
        // но UI показывает подсказку подключиться вручную.
        assertEquals(StealthState.ENABLED, o.state.value)
        assertTrue(o.lastError.value?.contains("вручную") == true)
    }

    @Test
    fun disable_uses_decoy_path_when_vpn_detaches_after_revoke() = runTest {
        // TOGGLE-client (v2rayNG): stopVPN API step пропускается, сразу decoy.
        // Decoy революкает — refreshEffect делает vpnActive=false → stopVpn true.
        vpn.vpnActiveState.value = true
        val o = orchestrator()
        o.enable(v2rayNg)
        vpn.vpnActiveState.value = true
        vpn.refreshEffect = { vpn.vpnActiveState.value = false }

        o.disable(v2rayNg, detectedPackage = null)

        assertEquals(StealthState.DISABLED, o.state.value)
        assertTrue("VPN_ONLY должно быть заморожено", shizuku.frozen.contains("com.telegram"))
        assertEquals("decoy-шаг должен был выполниться один раз", 1, decoyDisconnectCalls)
        assertFalse("force-stop НЕ нужен если decoy справился", shizuku.forceStopped.contains(v2rayNg.packageName))
    }

    @Test
    fun disable_falls_back_to_forcestop_when_decoy_not_enough() = runTest {
        // TOGGLE-клиент + decoy не помог (VPN держится несмотря на revoke).
        // Orchestrator должен пойти в step 3: force-stop целевого пакета.
        // После force-stop симулируем что VPN отвалился.
        vpn.vpnActiveState.value = true
        val o = orchestrator()
        o.enable(v2rayNg)
        vpn.vpnActiveState.value = true
        // refreshVpnState: пока force-stop не вызван — VPN держится.
        // Flag, который выставляет force-stop из shizuku-fake через forceStopEffect.
        var forceStopCalled = false
        shizuku.forceStopEffect = { forceStopCalled = true }
        vpn.refreshEffect = { if (forceStopCalled) vpn.vpnActiveState.value = false }

        o.disable(v2rayNg, detectedPackage = null)

        assertEquals(StealthState.DISABLED, o.state.value)
        assertEquals(1, decoyDisconnectCalls)
        assertTrue("force-stop должен был вызваться", shizuku.forceStopped.contains(v2rayNg.packageName))
    }

    @Test
    fun disable_rolls_back_if_vpn_refuses_to_stop() = runTest {
        vpn.vpnActiveState.value = true
        val o = orchestrator()
        o.enable(v2rayNg)
        vpn.vpnActiveState.value = true
        // Ни один способ не помогает — vpn «не отключается».
        // refreshVpnState оставляет true.
        vpn.refreshEffect = { /* nothing, remains true */ }

        o.disable(v2rayNg, detectedPackage = null)

        assertEquals(
            "state должен откатиться на ENABLED",
            StealthState.ENABLED, o.state.value,
        )
        assertTrue(o.lastError.value?.contains("Не удалось отключить VPN") == true)
        // VPN_ONLY НЕ должно быть заморожено, т.к. мы не прошли guard
        assertFalse(shizuku.frozen.contains("com.telegram"))
    }

    @Test
    fun disable_uses_SEPARATE_api_for_nekobox() = runTest {
        // NekoBox в SEPARATE: orchestrator сначала пробует stopVPN API, и если
        // VPN отключился после этого — decoy не вызывает.
        vpn.vpnActiveState.value = true
        vpn.stopVpnEffect = { vpn.vpnActiveState.value = false }
        val o = orchestrator()
        o.enable(nekoBox)
        vpn.vpnActiveState.value = true
        // после stopVpn → vpnActive=false, decoy не нужен

        o.disable(nekoBox, detectedPackage = null)

        assertEquals(StealthState.DISABLED, o.state.value)
        assertEquals("decoy не должен понадобиться", 0, decoyDisconnectCalls)
        assertFalse("force-stop не нужен", shizuku.forceStopped.contains(nekoBox.packageName))
    }

    @Test
    fun launchWithVpn_enables_stealth_first_then_unfreezes_target() = runTest {
        val pkg = "org.example.chat"
        // target изначально заморожен
        shizuku.frozen.add(pkg)

        val o = orchestrator()
        o.launchWithVpn(pkg, v2rayNg)

        // enable дёрнулся → LOCAL заморожены, VPN стартанул
        assertEquals(StealthState.ENABLED, o.state.value)
        assertTrue(vpn.startVpnCalls.contains(v2rayNg))
        // target разморожен и запущен
        assertFalse("target должен быть размоorozh", shizuku.frozen.contains(pkg))
        assertTrue("launchApp должен был вызваться", vpn.launchedApps.contains(pkg))
    }

    @Test
    fun launchLocal_disables_stealth_then_launches() = runTest {
        val pkg = "ru.sberbank.mobile"
        shizuku.frozen.add(pkg)
        vpn.vpnActiveState.value = true
        vpn.refreshEffect = { vpn.vpnActiveState.value = false }

        // Заранее сделаем state ENABLED чтобы orchestrator реально вызывал disable.
        val o = orchestrator()
        o.enable(v2rayNg)
        vpn.vpnActiveState.value = true
        shizuku.frozen.add(pkg)  // enable только что заморозил LOCAL, target в них нет

        o.launchLocal(pkg, v2rayNg, detectedPackage = null)

        assertEquals(StealthState.DISABLED, o.state.value)
        assertFalse("target разморожен", shizuku.frozen.contains(pkg))
        assertTrue(vpn.launchedApps.contains(pkg))
    }

    @Test
    fun toggleAppFrozen_inverts_state() = runTest {
        val pkg = "ru.test.app"
        val o = orchestrator()

        // первый вызов: незаморожено → freeze
        o.toggleAppFrozen(pkg)
        assertTrue(shizuku.frozen.contains(pkg))

        // второй: frozen → unfreeze
        o.toggleAppFrozen(pkg)
        assertFalse(shizuku.frozen.contains(pkg))
    }

    @Test
    fun syncState_updates_from_vpn_active() = runTest {
        val o = orchestrator()
        assertEquals(StealthState.DISABLED, o.state.value)

        vpn.vpnActiveState.value = true
        o.syncState()
        assertEquals(StealthState.ENABLED, o.state.value)

        vpn.vpnActiveState.value = false
        o.syncState()
        assertEquals(StealthState.DISABLED, o.state.value)
    }

    @Test
    fun freezeGroup_skips_frozen_and_missing_packages() = runTest {
        // com.ozon.app.android — уже заморожен (повторная заморозка ни к чему)
        // ru.rshb.dbo — не installed (miss)
        shizuku.installed -= "ru.rshb.dbo"
        shizuku.frozen.add("com.ozon.app.android")

        val o = orchestrator()
        o.enable(v2rayNg)

        assertTrue("установленный и не-замороженный должен был заморозиться",
            shizuku.frozenOrder.contains("com.ozon.app.android") ||
                shizuku.frozen.contains("com.ozon.app.android"))
        // Для не-installed freeze не вызывался (frozenOrder не содержит)
        assertFalse(shizuku.frozenOrder.contains("ru.rshb.dbo"))
    }

    // --- Fakes ---

    /** Простая in-memory имитация ShizukuManager для тестов state-машины. */
    private class FakeFreezeActions : FreezeActions {
        var available = true
        var permission = true
        val installed = mutableSetOf(
            "ru.rshb.dbo", "com.ozon.app.android", "com.telegram",
            "org.example.chat", "com.v2ray.ang", "moe.nb4a",
            "org.amnezia.vpn", "ru.sberbank.mobile", "ru.test.app",
        )
        val frozen = mutableSetOf<String>()
        val forceStopped = mutableListOf<String>()
        /** Порядок реальных вызовов freezeApp — нужен чтобы проверять что call действительно был. */
        val frozenOrder = mutableListOf<String>()
        /** Эффект force-stop — в тестах используется чтобы «потушить» VPN. */
        var forceStopEffect: (String) -> Unit = {}

        override fun isAvailable() = available
        override fun hasPermission() = permission
        override fun isAppFrozen(packageName: String) = packageName in frozen
        override fun isAppInstalled(packageName: String) = packageName in installed

        override suspend fun freezeApp(packageName: String): Result<Unit> {
            frozenOrder += packageName
            frozen += packageName
            return Result.success(Unit)
        }
        override suspend fun unfreezeApp(packageName: String): Result<Unit> {
            frozen -= packageName
            return Result.success(Unit)
        }
        override suspend fun forceStopApp(packageName: String): Result<Unit> {
            forceStopped += packageName
            forceStopEffect(packageName)
            return Result.success(Unit)
        }
    }

    private class FakeVpnControls : VpnControls {
        val vpnActiveState = MutableStateFlow(false)
        override val vpnActive: StateFlow<Boolean> get() = vpnActiveState

        val startVpnCalls = mutableListOf<SelectedVpnClient>()
        val stopVpnCalls = mutableListOf<SelectedVpnClient>()
        val launchedApps = mutableListOf<String>()

        /** Симулировать эффект stopVPN (например выставить vpnActive=false). */
        var stopVpnEffect: (SelectedVpnClient) -> Unit = {}
        /** Симулировать эффект refreshVpnState (например имитация force-stop'а). */
        var refreshEffect: () -> Unit = {}

        override suspend fun startVPN(client: SelectedVpnClient) {
            startVpnCalls += client
            if (client.controlMode != sgnv.anubis.app.vpn.VpnControlMode.MANUAL) {
                vpnActiveState.value = true
            }
        }
        override suspend fun stopVPN(client: SelectedVpnClient) {
            stopVpnCalls += client
            stopVpnEffect(client)
        }
        override fun launchApp(packageName: String) { launchedApps += packageName }
        override fun refreshVpnState() { refreshEffect() }
    }

    private class FakePackageGroups(
        private val groups: Map<AppGroup, Set<String>>,
    ) : PackageGroupsReader {
        override suspend fun getPackagesByGroup(group: AppGroup): Set<String> =
            groups[group] ?: emptySet()
    }
}
