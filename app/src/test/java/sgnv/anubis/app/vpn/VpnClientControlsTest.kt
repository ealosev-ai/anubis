package sgnv.anubis.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверяем что для каждого известного VPN-клиента из VpnClientType есть
 * корректный VpnClientControl. Любая регрессия в маппинге (переименовали
 * action, убрали startCommand) взорвёт этот тест сразу.
 */
class VpnClientControlsTest {

    @Test
    fun every_known_type_has_a_control_entry() {
        // getControl бросит NPE если тип не в map — значит map покрывает все
        // enum-варианты. Гарантия что при добавлении нового клиента разработчик
        // не забудет зарегистрировать его в VpnClientControls.
        for (type in VpnClientType.entries) {
            val control = VpnClientControls.getControl(type)
            assertEquals(type, control.clientType)
        }
    }

    @Test
    fun nekobox_is_SEPARATE_with_distinct_start_stop() {
        val control = VpnClientControls.getControl(VpnClientType.NEKO_BOX)
        assertEquals(VpnControlMode.SEPARATE, control.mode)
        assertNotNull(control.startCommand)
        assertNotNull(control.stopCommand)
        // Команды должны различаться (QuickEnable vs QuickDisable).
        assertEquals(false, control.startCommand!!.contentEquals(control.stopCommand!!))
    }

    @Test
    fun v2rayng_is_TOGGLE_with_widget_broadcast() {
        val control = VpnClientControls.getControl(VpnClientType.V2RAY_NG)
        assertEquals(VpnControlMode.TOGGLE, control.mode)
        assertNotNull(control.startCommand)
        // Шлём am broadcast на WidgetProvider
        assertArrayEquals(
            arrayOf(
                "am", "broadcast",
                "-a", "com.v2ray.ang.action.widget.click",
                "-n", "com.v2ray.ang/.receiver.WidgetProvider",
            ),
            control.startCommand,
        )
        // У TOGGLE нет отдельного stop — toggle тем же.
        assertNull(control.stopCommand)
    }

    @Test
    fun amnezia_is_MANUAL_without_shell_commands() {
        val control = VpnClientControls.getControl(VpnClientType.AMNEZIA)
        assertEquals(VpnControlMode.MANUAL, control.mode)
        assertNull(control.startCommand)
        assertNull(control.stopCommand)
    }

    @Test
    fun unknown_package_falls_back_to_MANUAL() {
        val control = VpnClientControls.getControlForPackage("io.fictional.vpn")
        assertEquals(VpnControlMode.MANUAL, control.mode)
        assertNull(control.startCommand)
    }

    @Test
    fun known_package_returns_correct_control() {
        // Передаём реальный packageName — должен зарезолвиться в тот же control
        // что и прямой getControl(type).
        val direct = VpnClientControls.getControl(VpnClientType.HAPP)
        val byPkg = VpnClientControls.getControlForPackage(VpnClientType.HAPP.packageName)
        assertEquals(direct.clientType, byPkg.clientType)
        assertEquals(direct.mode, byPkg.mode)
        assertArrayEquals(direct.startCommand, byPkg.startCommand)
    }
}
