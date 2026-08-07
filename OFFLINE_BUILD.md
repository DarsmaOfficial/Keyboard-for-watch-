# Building offline

Spec §3.1 requires that this project build without network access **from a clean machine**, not
merely from a warm Gradle cache — and it names the difference explicitly, because "it built on my
laptop" usually means the artifacts were already downloaded months ago.

This document is the procedure. What makes it checkable rather than aspirational:

| Piece | Where | What it guarantees |
|---|---|---|
| Version catalogue | `gradle/libs.versions.toml` | One place declares every external dependency |
| Lockfiles | `*/gradle.lockfile` | 96 coordinates pinned, transitives included |
| Gradle wrapper checksum | `gradle/wrapper/gradle-wrapper.properties` | The build tool itself is verified |

## What is actually depended on

Three declared dependencies, and that is the whole list:

- `androidx.dynamicanimation:dynamicanimation` — spring physics for the key press (§8.0)
- `androidx.test.ext:junit`, `androidx.test:runner` — instrumented smoke test only (§9), never in
  the shipped APK

Everything else in the lockfiles is transitive. The dictionary, layouts, swipe engine and emoji
catalogue are all first-party, which is why the list is this short.

## Priming a local mirror

The one-time step that needs network. Run it on a connected machine, then carry `~/.gradle` or the
mirror directory to the offline one.

```sh
# Populate the Gradle cache with exactly what the lockfiles pin
./gradlew :app:assembleDebug :app:assembleRelease --no-daemon

# Verify nothing outside the lock state was pulled in
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

To build a genuinely portable mirror rather than relying on `~/.gradle`, add to
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("file:///path/to/mirror") }
    }
}
```

and populate it once with a tool such as `gradle-download-dependencies`, or by copying the
`modules-2` cache. This is deliberately *not* wired in by default: hard-coding a local path into
the build would break every other checkout.

## Building with the network off

```sh
./gradlew :app:assembleDebug --offline --no-daemon
```

`--offline` makes Gradle refuse to reach the network at all. Combined with locking, a build that
succeeds under `--offline` has proven it needs nothing beyond what is already local and pinned.

## Verifying that locking is real

Lockfiles that are never checked are decoration. This was tested by tampering, not assumed:

1. A pinned version in `ui-wear/gradle.lockfile` was changed to a version that does not exist.
2. CI failed with
   `Did not resolve 'androidx.dynamicanimation:dynamicanimation:9.9.9' which is part of the
   dependency lock state`.
3. The tampered branch was deleted.

That is the behaviour to expect if a dependency ever moves underneath the project.

## After changing a dependency

Lockfiles must be regenerated or the build will fail — which is the point.

```sh
./gradlew resolveAndLockAll --write-locks --no-daemon
```

In this project's sandbox that command cannot run: there is no Android SDK, and the JVM cannot
start at all under the PaX/W^X restriction (`Failed to mark memory page as executable`). Use the
**Refresh dependency locks** workflow in GitHub Actions instead — it runs the same command and
commits the result.
