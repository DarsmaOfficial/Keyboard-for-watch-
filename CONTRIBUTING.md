# Contributing

Pull requests are welcome. There is no CLA or copyright assignment. You keep the copyright to your
work and agree to publish the contribution under Apache-2.0 when you open a pull request.

## Project rules

A few constraints are part of the design rather than preferences:

1. Do not add `android.permission.INTERNET`. Spell-checking, translation, crash reporting and other
   features that need a server are out of scope, including optional versions of them.
2. Do not add microphone access or voice input.
3. Code and data shipped in the APK must use MIT, Apache-2.0 or BSD terms. Record any new
   dependency, font, icon set or dataset in `THIRD_PARTY_LICENSES.md`.
4. The project must not depend on a paid service, quota-limited free tier, account or app store.
5. Do not add native code. The main test watch is 32-bit-only, and keeping the APK pure Kotlin/JVM
   avoids ABI-specific failures.
6. The typing UI stays on Android `View` and `Canvas`; do not add Compose to `:ui-wear` or `:app`.
7. Never log text handled by the keyboard, including in debug builds.

CI checks several of these automatically, but a passing build is not a substitute for keeping the
reason behind them intact.

## Before opening a pull request

Run the relevant tests and build the APK:

```sh
./gradlew :ime-core:test :dict:test :layout-engine:test :engine-swipe:test \
  :ui-wear:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Changes to editor state, clipboard behavior, correction or gesture recognition should include a
unit test. Changes to key geometry, the text strip, panels or window sizing should also be checked
on a round watch. A physical photograph is useful for bezel clipping that `adb screencap` cannot
show.

## Code style

- Use the official Kotlin style.
- Explain unusual decisions in comments, particularly where the watch behaves differently from a
  phone.
- Avoid allocations in draw and touch loops.
- Keep user-facing English and Russian strings in step.

## Bug reports

Include the watch model, Wear OS version, the app or reply surface in use, and reproducible steps.
Do not paste passwords, message contents, clipboard entries or other private text into an issue.
For visual bugs, attach a photograph of the watch if possible.
