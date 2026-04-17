package sgnv.anubis.app.audit

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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.audit.model.AuditHit
import java.net.Socket

/**
 * Настоящий Shizuku end-to-end.
 *
 * Требования:
 *   1. Shizuku demon запущен на устройстве/эмуляторе
 *      (на debug-сборке: adb shell на libshizuku.so из /data/app/.../lib/)
 *   2. sgnv.anubis.app.debug выдано разрешение Shizuku ("Allow all the time")
 *
 * Если условия не выполнены — тест пропускается через Assume, а не падает.
 *
 * Что проверяем:
 *   HoneypotListener из AnubisApp (продовый инстанс с настоящим ShizukuManager,
 *   NativeUidResolver, PackageResolver) + реальный клиентский сокет в том же
 *   процессе → uid через /proc/net/tcp читается Shizuku-shell'ом, pkg — через
 *   PackageManager. Результат: hit.uid = Process.myUid(), hit.packageName
 *   = наш applicationId.
 *
 * Это и есть тот e2e что hjhdgaps заменял HoneypotFullE2ETest (без Shizuku)
 * проскипывал из-за getConnectionOwnerUid/loopback ограничения.
 */
@RunWith(AndroidJUnit4::class)
class HoneypotShizukuE2ETest {

    private lateinit var app: AnubisApp
    private lateinit var listener: HoneypotListener

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as AnubisApp
        // Ждём пока ShizukuManager закешируется в статус READY и UserService забиндится.
        // В CI без Shizuku → быстро fail → тест будет skipped.
        runBlocking {
            val ok = app.shizukuManager.awaitShizukuReady(totalTimeoutMs = 5_000L)
            assumeTrue(
                "Shizuku не доступен (демон не запущен или нет разрешения) — тест пропущен",
                ok,
            )
        }
        listener = app.auditListener
    }

    @After
    fun tearDown() {
        // setUp мог assumeTrue-пропустить тест до того как listener установился —
        // в этом случае lateinit бросит UninitializedPropertyAccessException, и
        // JUnit выдаст TestCouldNotBeSkippedException вместо skip. Проверяем
        // через ::listener.isInitialized перед вызовом.
        if (::listener.isInitialized) listener.stop()
    }

    @Test
    fun shizuku_resolves_uid_and_package_on_self_loopback() = runBlocking {
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
            sock.getInputStream().read(reply)
        }

        val hit = withTimeout(5000) { firstHit.await() }
        assertEquals(port, hit.port)
        assertEquals("TCP", hit.protocol)

        // Главное: Shizuku через /proc/net/tcp + PackageManager даёт нам uid/pkg
        // ДАЖЕ для loopback — в отличие от getConnectionOwnerUid.
        assertEquals(
            "Shizuku должен отдать наш собственный uid через /proc/net/tcp",
            Process.myUid(),
            hit.uid,
        )
        assertNotNull("package должен резолвиться через PackageManager", hit.packageName)
        // instrumentation-тесты бегут в процессе target app (main, не .test) —
        // package у hit должен совпадать с applicationId main-сборки.
        val expectedPkg = app.packageName
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
}
