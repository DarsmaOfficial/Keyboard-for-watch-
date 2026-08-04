plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.darsma.wearkey"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.darsma.wearkey"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // enable + tune R8 rules once app is functional (phase 5)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Phase 0 hard gate (§10.6 / §8.0): the IME module must never pull in
    // androidx.compose.* — verify with `./gradlew :app:dependencies` before every release.
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":ui-wear"))
    implementation(project(":ime-core"))
    // Intentionally minimal for Phase 0: no Compose, no NDK, no INTERNET-requiring libs.
    // androidx.dynamicanimation is added in Phase 3 (motion) — not needed to draw a static grid.
}
