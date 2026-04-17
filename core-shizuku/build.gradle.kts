plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sgnv.anubis.core.shizuku"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        // BuildConfig нам в этом модуле не нужен — applicationId передаётся извне.
    }
}

dependencies {
    // api: Shizuku-типы (ShellExec) торчат наружу, это часть публичного API модуля.
    api("dev.rikka.shizuku:api:13.1.5")
    // provider: ShizukuProvider регистрируется в AndroidManifest хост-приложения;
    // сам класс дергается из :app, поэтому оставляем как api зависимость.
    api("dev.rikka.shizuku:provider:13.1.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
