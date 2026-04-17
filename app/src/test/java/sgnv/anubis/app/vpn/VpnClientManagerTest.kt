package sgnv.anubis.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import sgnv.anubis.app.shizuku.ShellExec

/**
 * Unit-тесты VpnClientManager: маршрутизация startVPN/stopVPN по
 * VpnControlMode, fallback на launchApp при сбое shell, detectActiveVpnClient
 * через моковый dumpsys-вывод, isInstalled через mock PackageManager.
 */
class VpnClientManagerTest {

    private lateinit var context: Context
    private lateinit var pm: PackageManager
    private lateinit var shell: RecordingShell

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        pm = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(pm)
        shell = RecordingShell()
    }

    private fun manager() = VpnClientManager(context, shell)

    // --- startVPN ---

    @Test
    fun startVPN_nekobox_SEPARATE_executes_shell_start_command() = runTest {
        val m = manager()
        m.startVPN(SelectedVpnClient.fromKnown(VpnClientType.NEKO_BOX))

        val expectedCmd = VpnClientControls.getControl(VpnClientType.NEKO_BOX).startCommand!!
        assertEquals(1, shell.execCalls.size)
        assertEquals(expectedCmd.toList(), shell.execCalls[0].toList())
    }

    @Test
    fun startVPN_SEPARATE_falls_back_to_launchApp_on_shell_failure() = runTest {
        shell.execResult = Result.failure(RuntimeException("boom"))
        val intent = Intent(Intent.ACTION_MAIN)
        `when`(pm.getLaunchIntentForPackage(VpnClientType.NEKO_BOX.packageName)).thenReturn(intent)

        val m = manager()
        m.startVPN(SelectedVpnClient.fromKnown(VpnClientType.NEKO_BOX))

        // SEPARATE-команда всё равно была сделана
        assertEquals(1, shell.execCalls.size)
        // + fallback — launchApp вызвался
        verify(context).startActivity(any())
    }

    @Test
    fun startVPN_v2rayng_TOGGLE_when_vpn_off_executes_broadcast() = runTest {
        val m = manager()
        // _vpnActive = false по умолчанию
        m.startVPN(SelectedVpnClient.fromKnown(VpnClientType.V2RAY_NG))

        val expected = VpnClientControls.getControl(VpnClientType.V2RAY_NG).startCommand!!
        assertEquals(1, shell.execCalls.size)
        assertEquals(expected.toList(), shell.execCalls[0].toList())
    }

    @Test
    fun startVPN_amnezia_MANUAL_only_launches_app() = runTest {
        val intent = Intent(Intent.ACTION_MAIN)
        `when`(pm.getLaunchIntentForPackage(VpnClientType.AMNEZIA.packageName)).thenReturn(intent)

        val m = manager()
        m.startVPN(SelectedVpnClient.fromKnown(VpnClientType.AMNEZIA))

        // MANUAL — никаких shell-команд
        assertTrue("MANUAL не должен шеллить", shell.execCalls.isEmpty())
        verify(context).startActivity(any())
    }

    @Test
    fun startVPN_unknown_package_just_launches_app() = runTest {
        val intent = Intent(Intent.ACTION_MAIN)
        `when`(pm.getLaunchIntentForPackage("io.custom.vpn")).thenReturn(intent)

        val m = manager()
        m.startVPN(SelectedVpnClient.fromPackage("io.custom.vpn"))

        assertTrue(shell.execCalls.isEmpty())
        verify(context).startActivity(any())
    }

    @Test
    fun startVPN_when_launchIntent_null_does_nothing() = runTest {
        // Если pm не вернул launcher — не падаем (юзер мог удалить app).
        `when`(pm.getLaunchIntentForPackage(any())).thenReturn(null)
        val m = manager()
        m.startVPN(SelectedVpnClient.fromKnown(VpnClientType.AMNEZIA))

        verify(context, never()).startActivity(any())
    }

    // --- stopVPN ---

    @Test
    fun stopVPN_nekobox_SEPARATE_executes_stop_command() = runTest {
        val m = manager()
        m.stopVPN(SelectedVpnClient.fromKnown(VpnClientType.NEKO_BOX))

        val expected = VpnClientControls.getControl(VpnClientType.NEKO_BOX).stopCommand!!
        assertEquals(1, shell.execCalls.size)
        assertEquals(expected.toList(), shell.execCalls[0].toList())
    }

    @Test
    fun stopVPN_MANUAL_is_noop() = runTest {
        val m = manager()
        m.stopVPN(SelectedVpnClient.fromKnown(VpnClientType.AMNEZIA))

        assertTrue("MANUAL stop делегирует force-stop caller'у", shell.execCalls.isEmpty())
        verify(context, never()).startActivity(any())
    }

    // --- isInstalled / isEnabled ---

    @Test
    fun isInstalled_true_when_package_manager_returns_info() {
        `when`(pm.getApplicationInfo(VpnClientType.V2RAY_NG.packageName, 0))
            .thenReturn(ApplicationInfo())
        val m = manager()
        assertTrue(m.isInstalled(VpnClientType.V2RAY_NG))
    }

    @Test
    fun isInstalled_false_when_NameNotFound() {
        `when`(pm.getApplicationInfo(anyString(), anyInt()))
            .thenThrow(PackageManager.NameNotFoundException())
        val m = manager()
        assertFalse(m.isInstalled(VpnClientType.V2RAY_NG))
    }

    @Test
    fun isEnabled_true_only_when_info_enabled_flag_is_true() {
        val disabled = ApplicationInfo().apply { enabled = false }
        val enabled = ApplicationInfo().apply { enabled = true }
        `when`(pm.getApplicationInfo(VpnClientType.V2RAY_NG.packageName, 0)).thenReturn(disabled)
        `when`(pm.getApplicationInfo(VpnClientType.NEKO_BOX.packageName, 0)).thenReturn(enabled)

        val m = manager()
        assertFalse(m.isEnabled(VpnClientType.V2RAY_NG))
        assertTrue(m.isEnabled(VpnClientType.NEKO_BOX))
    }

    @Test
    fun getInstalledClients_filters_to_present_only() {
        // installed: только v2rayNG и Happ. Остальные бросают NameNotFound.
        `when`(pm.getApplicationInfo(anyString(), anyInt())).thenAnswer { inv ->
            when (inv.getArgument<String>(0)) {
                VpnClientType.V2RAY_NG.packageName, VpnClientType.HAPP.packageName -> ApplicationInfo()
                else -> throw PackageManager.NameNotFoundException()
            }
        }
        val m = manager()
        val present = m.getInstalledClients()
        assertEquals(setOf(VpnClientType.V2RAY_NG, VpnClientType.HAPP), present.toSet())
    }

    // --- detectActiveVpnClient ---

    @Test
    fun detectActiveVpnClient_returns_null_when_vpn_not_active() = runTest {
        val m = manager()
        // _vpnActive = false; сразу null без похода в shell
        val result = m.detectActiveVpnClient()
        assertNull(result)
        assertTrue("shell.runShell не должен был вызываться", shell.runCalls.isEmpty())
    }

    // --- Fakes ---

    private class RecordingShell : ShellExec {
        val execCalls = mutableListOf<Array<out String>>()
        val runCalls = mutableListOf<Array<out String>>()
        var execResult: Result<Unit> = Result.success(Unit)
        var runResponse: (Array<out String>) -> String? = { null }

        override suspend fun runShell(vararg args: String): String? {
            runCalls += args
            return runResponse(args)
        }
        override suspend fun execCommand(vararg args: String): Result<Unit> {
            execCalls += args
            return execResult
        }
    }
}
