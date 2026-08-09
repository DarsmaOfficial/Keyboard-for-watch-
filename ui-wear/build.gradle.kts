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
    implementation(project(":ime-core"))
    // Spec §9: layouts are declarative data, parsed here rather than hardcoded in the view.
    implementation(project(":layout-engine"))
    // Spec §8.0: real spring physics, not tween approximations. 31 KB, Apache-2.0, and
    // explicitly NOT androidx.compose — the CI gate still passes.
    implementation(libs.androidx.dynamicanimation)
    // Phase 0/1/2: plain View + Canvas only.
    // Small Apache-2.0 spring runtime; no Compose or native payload.
    // HARD RULE (spec §8.0): this module must never depend on androidx.compose.* —
    // verify with `./gradlew :ui-wear:dependencies` before every release.
}
