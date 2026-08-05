# WearKey

A free, fully offline keyboard for Wear OS — built because Gboard on a watch hides the very
text you are typing.

**The problem it solves:** on Wear OS the keyboard covers the text field. You type blind, and
correcting a mistake means guessing. WearKey constrains itself to the lower part of the display
so the app's real field stays visible, and additionally mirrors what you are composing in a
strip directly above the keys, with a caret you can tap or drag to reposition.

Primary target is the **OnePlus Watch 2**; it should install and run on generic Wear OS 3/4
devices (`minSdk 30`).

---

## Free, forever — and what that actually means

- **No cost, no tiers, no subscriptions, no trial.** Ever.
- **No account.** Download an APK, side-load it, done. Nothing to sign into.
- **No network permission at all.** `android.permission.INTERNET` is not in the manifest — the
  app is *incapable* of talking to a server, not merely configured not to. Verify it yourself:
  ```sh
  unzip -p app-release.apk AndroidManifest.xml | strings | grep -i internet   # no match
  ```
- **No microphone.** `RECORD_AUDIO` is not requested. There is no voice input and none is
  planned — this is a touch-only keyboard by design.
- **No telemetry, no analytics, no crash reporting SaaS, no ads.**
- **No cloud AI, no "smart" features that phone home.**

See [`PRIVACY.md`](PRIVACY.md) for the full statement, including exactly what is stored on the
device and how to erase it.

---

## Status

Early, but usable for typing. What works today:

- Installs, enables and is selectable as a system keyboard
- Works through **both** Wear OS text-entry paths: the standard `InputMethodService`, and the
  `LAUNCH_KEYBOARD` activity that notification replies / WhatsApp / browser fields actually use
- Live composition strip with a blinking caret; tap or drag on the strip to move the caret
- Round-display key geometry — rows are fitted to the circle so keys are not lost under the bezel
- English and Russian layouts with an in-keyboard language key
- Clipboard history: one-tap paste, pin, delete, clear-all, with automatic early expiry for
  OTP- and card-number-looking entries
- Password fields are masked — plaintext never enters the preview buffer

- Shift with caps lock, and a two-page symbol/number layer
- Vibration feedback, with an intensity setting including full off
- Settings screen with an offline "open source licences" viewer

Not done yet: autocorrect, glide typing, emoji layer, press animations.
See [`CHANGELOG.md`](CHANGELOG.md).

### Known limitation: TalkBack

The key grid is drawn on a `Canvas`, which means the system sees it as one opaque view rather
than a set of keys. Individual key presses are announced, but **touch-exploration mode does not
work properly** — sliding a finger to hear keys before committing them will not behave as it
should. Fixing this needs a virtual accessibility node hierarchy
(`AccessibilityNodeProvider`), which is not implemented yet. If you rely on TalkBack, this
keyboard is not usable for you today.

---

## Install

1. Download `app-debug.apk` (or a signed release APK) from the
   [Releases page](https://github.com/DarsmaOfficial/Keyboard-for-watch-/releases).
2. Install it on the watch:
   ```sh
   adb connect <watch-ip>:<port>       # Settings > Developer options > Wireless debugging
   adb install -r app-debug.apk
   ```
3. Enable and select it:
   ```sh
   adb shell ime enable dev.darsma.wearkey/.WearKeyImeService
   adb shell ime set    dev.darsma.wearkey/.WearKeyImeService
   ```
   Or on the watch: **Settings → General → Input → Keyboards**.

No computer? The same two `ime` commands can be run through
[Shizuku](https://shizuku.rikka.app/) on the watch itself.

The APK also works when copied device-to-device (Bluetooth, USB, a file transfer app) — this
project deliberately does not depend on any store or platform remaining available.

---

## Build from source

Requires a JDK 17 and the Android SDK (platform 36, build-tools 36.1.0). No network access is
needed beyond the initial dependency download.

```sh
git clone https://github.com/DarsmaOfficial/Keyboard-for-watch-.git
cd Keyboard-for-watch-
./gradlew :app:assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Run the unit tests:

```sh
./gradlew :ime-core:test
```

Continuous integration is a convenience only — the build must always work offline on a
developer machine.

---

## Architecture, briefly

| Module | Contents |
|---|---|
| `:ime-core` | Pure Kotlin/JVM. `EditorState` (composition/caret state machine) and `ClipboardStore`. No Android dependencies, unit-testable without a device. |
| `:ui-wear` | The keyboard surface: key grid, composition strip, clipboard panel. Plain `View` + `Canvas` — deliberately **zero** `androidx.compose.*` dependencies, enforced in CI. |
| `:app` | `InputMethodService`, the `LAUNCH_KEYBOARD` activity, manifest. |

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the reasoning.

---

## Licence

Apache-2.0 — see [`LICENSE`](LICENSE), [`NOTICE`](NOTICE) and
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

Apache-2.0 was chosen partly for its express patent grant, which MIT and GPL-2.0 lack. A
consequence, and it is binding: code may only be borrowed from MIT / Apache-2.0 / BSD sources.
GPL and AGPL keyboards may be studied for ideas, never copied from.
