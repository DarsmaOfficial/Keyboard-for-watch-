plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.darsma.wearkey.uiwear"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
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
    // Phase 0/1/2: plain View + Canvas only.
    // androidx.dynamicanimation (spring physics, 31 KB) is added in Phase 3 — not yet.
    // HARD RULE (spec §8.0): this module must never depend on androidx.compose.* —
    // verify with `./gradlew :ui-wear:dependencies` before every release.
}
