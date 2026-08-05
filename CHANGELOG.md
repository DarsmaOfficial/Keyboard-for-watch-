# Changelog

All notable changes to this project are recorded here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow semantic versioning once 1.0
is reached.

## [Unreleased]

### Added
- Clipboard history (spec §6): panel replacing the key grid, one-tap paste, pin/unpin, delete,
  clear-all. Entries that look like one-time codes or card numbers are flagged and expire after
  two minutes unless pinned. The system clipboard is read only while the keyboard holds focus,
  and never from fields flagged `IME_FLAG_NO_PERSONALIZED_LEARNING`.
- Repository legal deliverables: `LICENSE`, `NOTICE`, `THIRD_PARTY_LICENSES.md`, `PRIVACY.md`,
  `CONTRIBUTING.md`, `ARCHITECTURE.md`, this changelog.
- Release workflow publishing a downloadable APK plus SHA-256 checksum to GitHub Releases.
- CI gates: no native libraries in the APK, no `INTERNET` / `RECORD_AUDIO` permission, no
  logging calls anywhere in the keyboard source, APK size against the 15 MB budget.

### Fixed
- **The keyboard no longer fills the display.** It now occupies the lower 66% of the screen so
  the app's real text field stays visible above it. Previously the composition strip mirrored
  the text but the field itself was completely hidden — which reproduced the exact Gboard
  failure this project exists to fix.
- Round-display key geometry: each row is fitted to the chord of the display circle, so the
  outer keys (Q/P, A/L, Z/M) are no longer clipped by the bezel. The circle is derived from the
  view's real position on screen rather than from `displayMetrics`, which describes the IME
  window and produced keys that overhung the glass.
- Language key handed control to Gboard instead of switching layouts
  (`switchToNextInputMethod(false)` cycles across all installed IMEs; it must be `true`).
  A local layout-swap fallback now runs when the framework declines to cycle, which it does when
  only one subtype is implicitly enabled.

## [0.1.0-phase1] — 2026-08-04

### Added
- `InputMethodService` entry point plus a `LAUNCH_KEYBOARD` activity, so the keyboard is
  reachable from both Wear OS text-entry paths (verified on hardware: the system chooser lists
  WearKey alongside Gboard).
- `EditorState` — the local composition/caret state machine, with unit tests. The keyboard never
  queries `InputConnection` per frame.
- Live composition strip with a blinking caret, horizontal auto-scroll, tap-to-position and
  drag-to-scrub.
- Masked-field handling: password, visible-password, web-password and numeric-PIN fields never
  place plaintext into the preview buffer.
- English (QWERTY) and Russian (ЙЦУКЕН) layouts with an in-keyboard language key and real
  `InputMethodSubtype` wiring.
- Key grid with backspace, space and enter.

## [0.1.0-phase0] — 2026-08-04

### Added
- Project skeleton: `:app`, `:ui-wear`, `:ime-core` modules, Gradle wrapper, CI.
- Trivial `Canvas` key grid to confirm both entry points render on real hardware.
- Frame-time instrumentation.
