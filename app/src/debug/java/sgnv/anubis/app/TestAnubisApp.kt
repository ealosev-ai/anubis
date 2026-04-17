package sgnv.anubis.app

import sgnv.anubis.app.vpn.VpnClientManager

/**
 * Application-подкласс для Compose UI-тестов.
 *
 * Что отличается от прод [AnubisApp]:
 *   - [createVpnClientManager] возвращает [FakeVpnClientManager] — shell-команды
 *     не летят, NetworkCallback не регистрируется, `vpnActive` переключается
 *     программно.
 *   - [startRuntimeMonitoring] — no-op. Нам не нужен реальный VPN monitor и
 *     honeypot listener в сценарных тестах.
 *
 * Указан как android:name в `app/src/androidTest/AndroidManifest.xml` через
 * tools:replace="android:name".
 */
class TestAnubisApp : AnubisApp() {

    override fun createVpnClientManager(): VpnClientManager =
        FakeVpnClientManager(this, shizukuManager)

    override fun startRuntimeMonitoring() {
        // Никаких NetworkCallback, honeypot, HitNotifier в UI-тестах.
    }
}

/** Удобный аксессор для тестов: кастим VpnClientManager к Fake. */
val AnubisApp.fakeVpn: FakeVpnClientManager
    get() = vpnClientManager as FakeVpnClientManager
