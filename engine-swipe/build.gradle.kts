// Spec §7.3: pure-Kotlin DTW path matching for glide typing.
//
// Pure Kotlin/JVM like :ime-core and :layout-engine — no Android types, so the recogniser is
// unit-tested on the JVM with no device. Spec §3 forbids the proprietary AOSP gesture binaries,
// so this is written from first principles.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
