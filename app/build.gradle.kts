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
        versionCode = 6
        versionName = "0.3.0"
    }

    // Release signing is driven entirely by environment variables so the keystore and its
    // passwords never enter the repository (spec §13: losing or leaking this key is the one
    // irreversible mistake in the project). When the variables are absent — the normal case for
    // a local debug build — the release build type simply stays unsigned.
    val keystorePath = System.getenv("WEARKEY_KEYSTORE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("WEARKEY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WEARKEY_KEY_ALIAS")
                keyPassword = System.getenv("WEARKEY_KEY_PASSWORD")
            }
        }
    }

    androidResources {
        // The dictionary indexes are memory-mapped at runtime so they cost mapped pages instead of
        // Java heap (spec §4.2). A compressed asset cannot be mapped — AssetManager can only hand
        // back a file descriptor for stored entries — so .bin must be excluded from compression.
        // These files are already compact primitive tables; leaving them uncompressed costs about
        // 1.3 MB of APK and saves 15.5 MB of heap.
        noCompress.add("bin")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // enable + tune R8 rules once app is functional (phase 5)
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(project(":layout-engine"))
    implementation(project(":dict"))
    implementation(project(":engine-swipe"))
    // Intentionally minimal for Phase 0: no Compose, no NDK, no INTERNET-requiring libs.
    // androidx.dynamicanimation is added in Phase 3 (motion) — not needed to draw a static grid.
}
