package sgnv.anubis.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Подменяет AnubisApp на TestAnubisApp при запуске инструментированных тестов.
 *
 * Android instrumentation test classloader для AUT (app under test) использует
 * базовый AndroidJUnitRunner.newApplication — его мы переопределяем и указываем
 * наш TestAnubisApp, лежащий в app/src/debug/java (поэтому он есть в AUT APK).
 *
 * Путь через AndroidManifest `tools:replace="android:name"` НЕ работает —
 * он меняет application class только для test APK process, а инструментация
 * стартует AUT в отдельном процессе, где manifest-override test'ов не применяется.
 *
 * Регистрация: app/build.gradle.kts → defaultConfig.testInstrumentationRunner
 */
class AnubisTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, ctx: Context): Application {
        return super.newApplication(cl, TestAnubisApp::class.java.name, ctx)
    }
}
