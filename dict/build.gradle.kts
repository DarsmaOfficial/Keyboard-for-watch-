// Autocorrect engine (spec §7.2, §4.2).
//
// No third-party dependency. SymSpellKt (MIT) was used first and is a perfectly good library, but
// its in-memory form is Map<Long, ArrayList<String>> for the delete table plus Map<String, Double>
// for frequencies — three Java objects per delete variant, and a 10 000-word list generates 68 625
// variants. Measured on the watch (dumpsys meminfo, Dalvik Heap → Alloc) one resident dictionary
// cost 15.5 MB against the spec's 8 MB gate, having already come down from 39.8 MB when the list
// was cut from 30 000 words. The overhead is structural, so §4.2's pre-authorised fallback applies:
// a flat index read through a genuine mmap path. That is WordIndex, which needs nothing but the
// standard library.
//
// Dropping the dependency also removes the last non-Apache/BSD component from the APK.
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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
