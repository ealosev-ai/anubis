package sgnv.anubis.app.audit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.shizuku.FreezeActions

class HitActionResolverTest {

    private val selfPkg = "sgnv.anubis.app.debug"
    private val bankHit = AuditHit(
        timestampMs = 1_700_000_000,
        port = 1080,
        uid = 10234,
        packageName = "ru.rshb.dbo",
        handshakePreview = "05 01 00",
        sni = null,
        protocol = "TCP",
    )

    // --- helpers ---

    private fun freezer(
        installed: Set<String> = setOf("ru.rshb.dbo", "com.ozon.app.android"),
        frozen: MutableSet<String> = mutableSetOf(),
        freezeResult: (String) -> Result<Unit> = { Result.success(Unit) },
    ) = object : FreezeActions {
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun isAppFrozen(packageName: String) = packageName in frozen
        override fun isAppInstalled(packageName: String) = packageName in installed
        override suspend fun freezeApp(packageName: String): Result<Unit> {
            val r = freezeResult(packageName)
            if (r.isSuccess) frozen += packageName
            return r
        }
        override suspend fun unfreezeApp(packageName: String): Result<Unit> {
            frozen -= packageName
            return Result.success(Unit)
        }
        override suspend fun forceStopApp(packageName: String): Result<Unit> = Result.success(Unit)
    }

    private fun resolver(
        mode: HitNotifier.Mode,
        freeze: FreezeActions = freezer(),
    ) = HitActionResolver(freeze, selfPkg, { mode })

    // --- tests ---

    @Test
    fun off_mode_always_skips() = runTest {
        val action = resolver(HitNotifier.Mode.OFF).decide(bankHit)
        assertEquals(HitActionResolver.Action.Skip, action)
    }

    @Test
    fun null_package_skipped_even_in_auto() = runTest {
        val hitNoPkg = bankHit.copy(packageName = null)
        val action = resolver(HitNotifier.Mode.AUTO).decide(hitNoPkg)
        assertEquals(HitActionResolver.Action.Skip, action)
    }

    @Test
    fun self_package_skipped_to_avoid_suicide() = runTest {
        // Наш собственный honeypot словил наш же сокет — не замораживаем себя.
        val selfHit = bankHit.copy(packageName = selfPkg)
        val action = resolver(HitNotifier.Mode.AUTO).decide(selfHit)
        assertEquals(HitActionResolver.Action.Skip, action)
    }

    @Test
    fun not_installed_package_skipped() = runTest {
        val fizzleHit = bankHit.copy(packageName = "com.fictional.app")
        val action = resolver(HitNotifier.Mode.AUTO).decide(fizzleHit)
        assertEquals(HitActionResolver.Action.Skip, action)
    }

    @Test
    fun ask_mode_returns_ShowAsk_without_touching_freeze() = runTest {
        val frozen = mutableSetOf<String>()
        val f = freezer(frozen = frozen)
        val action = resolver(HitNotifier.Mode.ASK, f).decide(bankHit)
        assertTrue(action is HitActionResolver.Action.ShowAsk)
        assertEquals("ru.rshb.dbo", (action as HitActionResolver.Action.ShowAsk).pkg)
        assertTrue("ASK не должен замораживать", frozen.isEmpty())
    }

    @Test
    fun auto_mode_freezes_and_returns_ShowAuto() = runTest {
        val frozen = mutableSetOf<String>()
        val f = freezer(frozen = frozen)
        val action = resolver(HitNotifier.Mode.AUTO, f).decide(bankHit)
        assertTrue(action is HitActionResolver.Action.ShowAuto)
        val a = action as HitActionResolver.Action.ShowAuto
        assertEquals("ru.rshb.dbo", a.pkg)
        assertEquals(false, a.freezeSkipped)
        assertTrue("после AUTO пакет должен быть заморожен", "ru.rshb.dbo" in frozen)
    }

    @Test
    fun auto_mode_skips_freeze_if_already_frozen() = runTest {
        // Уже заморожен → freezeApp не дёргаем (чтобы не было лишних pm-вызовов).
        val frozen = mutableSetOf("ru.rshb.dbo")
        var freezeCalls = 0
        val f = freezer(frozen = frozen, freezeResult = { freezeCalls++; Result.success(Unit) })
        val action = resolver(HitNotifier.Mode.AUTO, f).decide(bankHit)

        assertTrue(action is HitActionResolver.Action.ShowAuto)
        assertEquals(true, (action as HitActionResolver.Action.ShowAuto).freezeSkipped)
        assertEquals("freezeApp не должен вызваться", 0, freezeCalls)
    }

    @Test
    fun auto_mode_returns_FreezeFailed_on_error() = runTest {
        val err = RuntimeException("pm disable-user failed")
        val f = freezer(
            frozen = mutableSetOf(),
            freezeResult = { Result.failure(err) },
        )
        val action = resolver(HitNotifier.Mode.AUTO, f).decide(bankHit)
        assertTrue(action is HitActionResolver.Action.FreezeFailed)
        val a = action as HitActionResolver.Action.FreezeFailed
        assertEquals("ru.rshb.dbo", a.pkg)
        assertEquals(err, a.error)
    }
}
