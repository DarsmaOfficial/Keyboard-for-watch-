// Spec §7.2: SymSpellKt (MIT, pure Kotlin Multiplatform — no JNI, no native binary, therefore
// no ABI risk on this 32-bit-only watch). maxEditDistance is fixed at 1 per §4.2: distance 2
// costs 15-16 MB per language and is rejected outright.
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
    implementation(project(":ime-core"))
    // Note the capitalisation: the published Maven Central coordinate is "SymSpellKt", and the
    // JVM variant is resolved automatically from the Kotlin Multiplatform metadata.
    api("com.darkrockstudios:symspellkt:3.4.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
