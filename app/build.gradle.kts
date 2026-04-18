import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "sgnv.anubis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "sgnv.anubis.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
        // Custom runner подменяет AnubisApp на TestAnubisApp в тестах —
        // для Compose UI-сценариев важно не тянуть реальный VpnClientManager.
        testInstrumentationRunner = "sgnv.anubis.app.AnubisTestRunner"
    }


    signingConfigs {
        create("release") {
            val props = Properties().apply {
                rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            // storeFile путь может быть абсолютным (keystore вне репо) или
            // относительным к корню проекта.
            val storePath = props.getProperty("storeFile", "release.keystore")
            val storeCandidate = File(storePath)
            storeFile = if (storeCandidate.isAbsolute) storeCandidate else rootProject.file(storePath)
            storePassword = props.getProperty("storePassword", "")
            keyAlias = props.getProperty("keyAlias", "")
            keyPassword = props.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            // Свой форк → свой applicationId `sgnv.anubis.app.fork`, чтобы
            // не конфликтовать с upstream-пакетом `sgnv.anubis.app` автора
            // (можно держать оба параллельно на одном устройстве).
            applicationIdSuffix = ".fork"
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug — отдельный applicationId чтобы не конфликтовать с upstream
            // `sgnv.anubis.app` и нашим release `sgnv.anubis.app.fork`. Все три
            // (upstream, наш release, наш debug) живут параллельно на устройстве.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "anubis-${variant.versionName}-${variant.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // aidl больше не нужен здесь — IUserService.aidl переехал в :core-shizuku.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Возвращать дефолты (0/null/false) вместо Stub-исключений, чтобы
            // тесты на чистой JVM не падали на android.util.Log.* и подобном.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room entities/Daos/migrations — в :core-data (Room как api там,
    // для app будет transitively). Ksp-compiler тоже в :core-data.

    // Library-модули: все sgnv.anubis.app.{data,shizuku,audit}.* оттуда.
    // core-audit уже api-transitively тянет :core-shizuku и :core-data,
    // но для явности оставляем прямые зависимости.
    implementation(project(":core-shizuku"))
    implementation(project(":core-data"))
    implementation(project(":core-audit"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Тесты (jvm/unit) — Android-framework не тянем, только чистая логика.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // mockito-core — только для Context mock в StealthOrchestratorTest
    // (Orchestrator принимает Context но его не использует при unit-сценариях).
    testImplementation("org.mockito:mockito-core:5.12.0")
    // org.json на Android — stub в JVM unit-тестах; настоящая реализация
    // нужна для UpdateCheckerParseTest.
    testImplementation("org.json:json:20240303")

    // Instrumentation-тесты: бегут на реальном устройстве/эмуляторе.
    // Room-testing даёт MigrationTestHelper для проверки миграций против
    // экспортированных JSON-схем.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Compose UI-тесты для scenario-flow (MainActivity + UI interactions).
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
