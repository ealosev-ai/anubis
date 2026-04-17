plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sgnv.anubis.core.audit"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Shell через Shizuku (ShellExec, ShizukuManager в проде). AuditHit/Suspect
    // тоже торчат наружу, но они внутри этого модуля.
    api(project(":core-shizuku"))
    // Room AuditHitDao/AuditHitEntity для персиста хитов; AppRepository
    // для interop (markAsLocal).
    api(project(":core-data"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
