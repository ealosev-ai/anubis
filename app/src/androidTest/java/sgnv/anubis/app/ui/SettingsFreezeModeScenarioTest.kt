package sgnv.anubis.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sgnv.anubis.app.AnubisApp
import sgnv.anubis.app.shizuku.FreezeMode

/**
 * E2E: пользователь переключает режим заморозки в Settings.
 *
 * Проверяем:
 *   - tap на "pm suspend" радио → ShizukuManager.freezeMode меняется на SUSPEND
 *   - prefs "freeze_mode" = "suspend" записан
 *   - переключение обратно на disable-user → возврат к DISABLE_USER
 */
@RunWith(AndroidJUnit4::class)
class SettingsFreezeModeScenarioTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app: AnubisApp by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AnubisApp
    }

    @Before
    fun setUp() {
        // Сброс к default'у перед каждым тестом.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("freeze_mode", "disable").apply()
        app.shizukuManager.freezeMode = FreezeMode.DISABLE_USER
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun tapping_pm_suspend_changes_freezeMode_and_persists_to_prefs() {
        // Переход на таб Настройки. Label в NavigationBar = "Настройки".
        composeRule.onNodeWithText("Настройки").performClick()
        composeRule.waitForIdle()

        // Скроллим до секции "Режим заморозки" и тапаем по "pm suspend".
        // Compose Test находит composables вне viewport — нужно performScrollTo.
        composeRule.onNodeWithText("pm suspend").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Проверяем ShizukuManager и prefs.
        assertEquals(FreezeMode.SUSPEND, app.shizukuManager.freezeMode)
        val stored = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("freeze_mode", null)
        assertEquals("suspend", stored)

        // Обратный клик на disable-user.
        composeRule.onNodeWithText("pm disable-user").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(FreezeMode.DISABLE_USER, app.shizukuManager.freezeMode)
    }
}
