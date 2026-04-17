package sgnv.anubis.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.data.model.AppGroup

/**
 * Проверяет что hit-action broadcast (как из нотификации HitNotifier)
 * реально добавляет пакет в LOCAL-группу в Room. Без реального Shizuku
 * receiver выходит раньше (awaitUserService таймаутит) — тогда тест
 * пропускается через Assume.
 *
 * Не трогаем настоящие пакеты через shizuku.freezeApp: pkg фиктивный,
 * isAppInstalled вернёт false, freeze-команда не полетит. Проверяем
 * только шаг setAppGroup → LOCAL, он идёт до isAppInstalled.
 */
@RunWith(AndroidJUnit4::class)
class FreezeActionReceiverTest {

    private val fakePkg = "com.anubis.test.dummy_${System.currentTimeMillis()}"

    private val app: AnubisApp by lazy {
        InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as AnubisApp
    }

    @After
    fun cleanup() = runBlocking {
        app.appRepository.removeApp(fakePkg)
    }

    @Test
    fun freeze_broadcast_puts_package_into_LOCAL_group() = runBlocking {
        assumeTrue(
            "Shizuku не доступен — receiver не дойдёт до setAppGroup",
            app.shizukuManager.awaitShizukuReady(5_000L),
        )

        // До: пакет не в БД
        assertEquals(null, app.appRepository.getAppGroup(fakePkg))

        // Отправляем тот же PendingIntent что строит HitNotifier.
        FreezeActionReceiver.pendingFreeze(app, fakePkg).send()

        // Receiver работает на IO scope, BD-write асинхронный — опрашиваем
        // до 5 секунд с 100ms шагом.
        val resolved = withTimeoutOrNull(5_000L) {
            while (app.appRepository.getAppGroup(fakePkg) != AppGroup.LOCAL) {
                delay(100)
            }
            true
        }
        assertEquals(true, resolved)
        assertEquals(AppGroup.LOCAL, app.appRepository.getAppGroup(fakePkg))
    }
}
