package sgnv.anubis.app.audit

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sgnv.anubis.app.audit.model.AuditHit
import sgnv.anubis.app.shizuku.ShellExec
import java.net.Socket

/**
 * Попытка end-to-end UID-резолва без Shizuku через
 * [ConnectivityManager.getConnectionOwnerUid] + [PackageManager.getPackagesForUid].
 *
 * **Известное ограничение:** без `NETWORK_STACK` permission (signature-level,
 * system app) API возвращает `INVALID_UID (-1)` для loopback-соединений —
 * даже когда оба конца в нашем процессе. Проверено на Android 14 AVD.
 * Тест остаётся на случай если Google когда-нибудь отпустит ограничение или
 * если запускаем на устройстве с подходящими правами.
 *
 * На практике в проде uid резолвится через Shizuku (`/proc/net/tcp`) —
 * это всё ещё единственный надёжный путь для **чужих** процессов.
 */
@RunWith(AndroidJUnit4::class)
class HoneypotFullE2ETest {

    private lateinit var listener: HoneypotListener
    private lateinit var shell: NoopShell

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        shell = NoopShell()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        listener = HoneypotListener(
            shell = shell,
            native = AndroidNativeUidResolver(cm),
            packages = AndroidPackageResolver(ctx.packageManager),
        )
    }

    @After
    fun tearDown() {
        listener.shutdown()
    }

    @Test
    fun socks5_hit_resolves_to_self_uid_and_package() = runBlocking {
        listener.start()
        val port = awaitFirstListening()
            ?: run { assumeTrue("нет свободных портов", false); return@runBlocking }

        val firstHit = subscribeForFirstHit(this)

        Socket("127.0.0.1", port).use { sock ->
            sock.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                flush()
            }
            val reply = ByteArray(2)
            sock.getInputStream().read(reply)  // прочитаем чтобы honeypot успел
        }

        val hit = withTimeout(5000) { firstHit.await() }
        assertEquals(port, hit.port)
        assertEquals("TCP", hit.protocol)

        // Главное — uid резолвился через ConnectivityManager и совпадает с нашим uid.
        // Если getConnectionOwnerUid вдруг не сработал на этой версии Android, uid=null —
        // тогда skip, это не ошибка теста, а отсутствие API.
        assumeNotNull("ConnectivityManager.getConnectionOwnerUid не вернул uid", hit.uid)
        assertEquals(Process.myUid(), hit.uid)

        // И package — через PackageManager.getPackagesForUid.
        assertNotNull("package должен резолвиться", hit.packageName)
        val expectedPkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertEquals(expectedPkg, hit.packageName)
    }

    private suspend fun awaitFirstListening(): Int? = withTimeoutOrNull(3000) {
        listener.portStatus.filter { it.state == PortState.LISTENING }.first().port
    }

    private suspend fun subscribeForFirstHit(scope: CoroutineScope): Deferred<AuditHit> {
        val subscribed = CompletableDeferred<Unit>()
        val deferred = scope.async {
            listener.hits.onSubscription { subscribed.complete(Unit) }.first()
        }
        subscribed.await()
        return deferred
    }

    /** Shell-заглушка: Shizuku-fallback нам в этом тесте не нужен. */
    private class NoopShell : ShellExec {
        override suspend fun runShell(vararg args: String): String? = null
    }
}
