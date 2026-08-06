// Spec §9: declarative JSON layouts, language-agnostic key→action mapping.
//
// Pure Kotlin/JVM like :ime-core — no Android types, so layouts are parsed and validated in unit
// tests without a device. The parser is hand-written rather than pulling in kotlinx.serialization
// or Gson: the schema is a dozen fields, and spec §12 forbids heavyweight dependencies in the
// keyboard path.
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
