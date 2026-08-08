plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.darsma.wearkey"
    compileSdk = 36

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        if (!keystorePath.isNullOrBlank()) {
            // Fail loudly on a *partial* configuration. The original guard silently skipped signing
            // whenever the file was missing, which meant a typo in the path produced an unsigned
            // release APK that looks perfectly normal until it will not install over the previous
            // version. If the operator asked for signing, they must get signing or an error.
            require(file(keystorePath).exists()) {
                "WEARKEY_KEYSTORE is set but no file exists at: $keystorePath"
            }
            val storePw = System.getenv("WEARKEY_KEYSTORE_PASSWORD")
            val alias = System.getenv("WEARKEY_KEY_ALIAS")
            val keyPw = System.getenv("WEARKEY_KEY_PASSWORD")
            require(!storePw.isNullOrBlank()) { "WEARKEY_KEYSTORE_PASSWORD is not set" }
            require(!alias.isNullOrBlank()) { "WEARKEY_KEY_ALIAS is not set" }
            require(!keyPw.isNullOrBlank()) { "WEARKEY_KEY_PASSWORD is not set" }

            create("release") {
                storeFile = file(keystorePath)
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
                // The keystore is PKCS12 (the modern default; JKS is deprecated). Naming it
                // explicitly stops Gradle guessing from the file extension, which is .jks here for
                // historical reasons even though the container is PKCS12.
                storeType = "pkcs12"
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
            // Measured on the physical OnePlus Watch 2 with Perfetto: R8 + resource shrinking cut
            // median true-cold tap-to-first-buffer latency from 921 ms to 372 ms and reduced the
            // APK from 5.7 MB to 3.0 MB. This is a measured production optimization, not a size-
            // only assumption; the full test/privacy/APK gates run against it before release.
            isMinifyEnabled = true
            isShrinkResources = true
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
    testImplementation(kotlin("test"))

    // Instrumented smoke test only (§9). These are androidTest-scoped, so they never enter the
    // shipped APK — the §8.0 no-Compose gate inspects releaseRuntimeClasspath, which excludes them.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Intentionally minimal for Phase 0: no Compose, no NDK, no INTERNET-requiring libs.
    // androidx.dynamicanimation is added in Phase 3 (motion) — not needed to draw a static grid.
}
