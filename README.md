# WearKey

<p align="center">
  <img src=".github/social-preview.png" alt="WearKey — an offline Wear OS keyboard that keeps text visible" width="100%">
</p>

<p align="center">
  <strong>See what you type on a watch.</strong><br>
  A free, fully offline keyboard for Wear OS.
</p>

<p align="center">
  <a href="https://github.com/DarsmaOfficial/Keyboard-for-watch-/actions/workflows/build.yml"><img src="https://github.com/DarsmaOfficial/Keyboard-for-watch-/actions/workflows/build.yml/badge.svg?branch=main" alt="Build status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-00E5FF?style=flat-square" alt="Apache-2.0 licence"></a>
  <img src="https://img.shields.io/badge/Wear%20OS-3%20%2F%204-10191C?style=flat-square" alt="Wear OS 3 and 4">
  <img src="https://img.shields.io/badge/network-permission%3A%20none-10191C?style=flat-square" alt="No network permission">
</p>

---

## The problem

On Wear OS, a keyboard can cover the field you are trying to edit. That makes typing a
small guessing game with an unhelpful ending.

WearKey leaves the upper screen available for the **real app field** and keeps a compact,
caret-aware composition strip directly above its keys. You can see what changed, move the caret,
and correct it before committing more text.

| What matters | How WearKey approaches it |
|---|---|
| **Text must stay visible** | Keyboard is constrained to the lower 66% of the display; the composition strip is a fallback mirror, not a replacement for the real field. |
| **A keyboard should not be a surveillance device** | No `INTERNET`, no microphone, no telemetry, no account, no cloud dependency. |
| **A watch needs watch-sized interaction** | Circular key geometry, haptics, caret scrubbing, English/Russian layouts, and a dedicated Wear `LAUNCH_KEYBOARD` entry point. |
| **Claims should be testable** | CI checks permissions, native libraries, Compose leakage, APK size, dictionary packaging, index drift, and keystroke logging. |

## Built for calm, offline typing

- **Live composition strip** — blinking caret; tap or drag to reposition it.
- **Two Wear OS entry points** — standard IME and `LAUNCH_KEYBOARD` for reply-oriented flows.
- **Round-first key grid** — probabilistic touch matching continues outer keys toward the screen edge.
- **English + Russian** — switch directly from the keyboard.
- **Autocorrect and glide typing** — frequency-ranked offline dictionaries; no text leaves the watch.
- **Spatial prediction** *(experimental, opt-in)* — resolves ambiguous taps when a word boundary is reached.
- **Clipboard history** — encrypted at rest, one-tap paste, pin/delete/clear; sensitive-looking entries expire early unless pinned.
- **Emoji, themes, tutorial, calibration** — all local; no bundled cloud features hiding in settings.
- **Input-aware safety** — password/PIN previews use bullets and never retain plaintext.

> [!IMPORTANT]
> WearKey is usable, but it is not yet fully hardware-certified. See
> [`STATUS.md`](STATUS.md) for the measured facts, deferred device checks, and the strict
> true-cold-start gate that currently fails.

## Privacy is an implementation detail, not a slogan

```text
No account · No telemetry · No ads · No microphone · No network permission
```

Without `android.permission.INTERNET`, the app cannot open a network connection. It does not
need a privacy policy that asks you to trust an exception list; its Android manifest makes the
most important promise enforceable. Read the exact storage and erasure rules in
[`PRIVACY.md`](PRIVACY.md).

## Install

1. Download a signed release APK from [Releases](https://github.com/DarsmaOfficial/Keyboard-for-watch-/releases).
2. Install and select it:

   ```sh
   adb connect <watch-ip>:<port>
   adb install -r app-release.apk
   adb shell ime enable dev.darsma.wearkey/.WearKeyImeService
   adb shell ime set dev.darsma.wearkey/.WearKeyImeService
   ```

   Or enable it on the watch through **Settings → General → Input → Keyboards**.

The primary target is **OnePlus Watch 2**. WearKey targets Wear OS 3/4 (`minSdk 30`) and is
pure Kotlin/JVM: no native ABI payloads, no store dependency, no companion service required.

## Build it yourself

Requires JDK 17 plus Android SDK platform 36 / build-tools 36.1.0.

```sh
git clone https://github.com/DarsmaOfficial/Keyboard-for-watch-.git
cd Keyboard-for-watch-
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. The project supports an offline build once
dependencies are present; see [`OFFLINE_BUILD.md`](OFFLINE_BUILD.md).

```sh
./gradlew :ime-core:test :dict:test :layout-engine:test :engine-swipe:test :ui-wear:testDebugUnitTest :app:testDebugUnitTest
```

## Project map

| Area | What lives there |
|---|---|
| [`:ime-core`](ime-core/) | Pure Kotlin editor state, caret/composition semantics, touch calibration, clipboard logic. |
| [`:dict`](dict/) | Packed memory-mapped dictionary index and frequency-ranked correction. |
| [`:engine-swipe`](engine-swipe/) | Glide recognition and spatial word resolver. |
| [`:ui-wear`](ui-wear/) | Canvas/View keyboard surface, panels, strip, motion and accessibility provider. |
| [`:app`](app/) | IME service, Wear launch activity, settings, assets and Android integration. |

For the decisions behind the layout, memory budget, dictionary format and lifecycle rules, read
[`ARCHITECTURE.md`](ARCHITECTURE.md). For the complete build/measurement state, read
[`STATUS.md`](STATUS.md).

## Contribute without breaking the point

This project accepts contributions under Apache-2.0, with no CLA or copyright assignment. The
constraints are deliberate: no network permission, microphone, native code, paid services,
tracking, GPL/AGPL code, or Compose in the keyboard path.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a PR.

## Licence

Apache-2.0 — [`LICENSE`](LICENSE) · [`NOTICE`](NOTICE) ·
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)
