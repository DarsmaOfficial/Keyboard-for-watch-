// Spec §9: pure Kotlin/JVM — EditorState, caret, InputConnection state machine, window insets.
// No Android UI, no android.* imports. Unit-testable without a device or emulator.
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
