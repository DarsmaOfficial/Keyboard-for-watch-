// Root build file — Wear OS keyboard (Apache-2.0)
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
}

// Dependency locking (spec §3.1).
//
// Without lockfiles, "this project builds offline" means "it builds on a machine whose Gradle cache
// already happens to hold the right artifacts" — which is not the same claim and is not
// reproducible. Locking pins every resolved coordinate, including the transitive ones nobody
// declared, so a build either resolves exactly what was recorded or fails and says what changed.
//
// Regenerate after any dependency change:
//     ./gradlew resolveAndLockAll --write-locks
subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    // A single task that resolves the lockable configurations, so `--write-locks` has something to
    // walk. Without it each configuration would have to be resolved by hand to refresh the locks.
    //
    // Only *runtime and compile classpaths* are resolved, and androidTest ones are excluded.
    // Resolving every resolvable configuration fails: the androidTest classpaths depend on the app
    // project itself, and outside a real build Gradle cannot choose between its variants
    // ("cannot choose between the following variants of project :app"). Those configurations carry
    // no external coordinate that is not already locked through the main classpaths, so skipping
    // them costs no coverage.
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Resolves configurations at execution time by design")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) {
                "Run with --write-locks: ./gradlew resolveAndLockAll --write-locks"
            }
        }
        doLast {
            configurations
                .filter { it.isCanBeResolved }
                .filter { it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") }
                .filterNot { it.name.contains("AndroidTest", ignoreCase = true) }
                .forEach { it.resolve() }
        }
    }
}
