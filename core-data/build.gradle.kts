plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "sgnv.anubis.core.data"
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
}

dependencies {
    // Room: таблицы и DAO — публичный API модуля (Entity/Dao-типы торчат
    // наружу для репозиториев), поэтому api, а не implementation.
    api("androidx.room:room-runtime:2.7.2")
    api("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // `org.json.*` на Android — часть framework, в JVM-тесте это stub (not mocked).
    // Для unit-тестов GroupsBackup нужна настоящая реализация.
    testImplementation("org.json:json:20240303")
}
