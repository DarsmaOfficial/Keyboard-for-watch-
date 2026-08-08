# Changelog

All notable changes to this project are recorded here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow semantic versioning once 1.0
is reached.

## [Unreleased]

### Fixed
- Tutorial controls now remain reachable on a 466×466 round display when translated text exceeds one screen height.
- Rejected touch-calibration fits can no longer be retained as pending values and later applied.
- **Keys no longer move under your finger.** The candidate row was removed from the layout when it
  had nothing to offer, so the moment a suggestion appeared mid-word every key row shifted down by
  its height and the next tap landed on the wrong thing — typing "helo" reliably produced "del".
  The row's height is now reserved permanently, so key geometry is fixed for as long as a field is
  focused.
- **Autocorrect suggests the word you meant.** The shipped word lists carried no frequency
  information, so all 10 000 entries loaded at the same weight and the ordering among
  equally-close candidates was arbitrary: "helo" offered "halo / held / helm" and never "hello".
  Each word now carries a real frequency, and candidates are ranked by it.
- **Java heap is back inside its budget.** One resident dictionary measured **39.8 MB** of Dalvik
  heap on the watch against the specification's 8 MB gate. Two causes, fixed in order: the lists
  shipped 30 000 words where the budget was calculated for 10 000 (cutting to 10 000 gave 15.5 MB),
  and SymSpellKt's in-memory delete table costs three Java objects per variant, of which 10 000
  words generate 68 625. The engine is now a packed binary index that is memory-mapped instead of
  allocated, so the dictionary occupies clean file-backed pages rather than Java heap.
  `ARCHITECTURE.md` records every measurement and the two `dumpsys` traps that made the earlier
  numbers misleading.

### Changed
- Word lists are now frequency-ranked rather than length-ranked, so the 10 000 kept words are the
  most *common* ones instead of the *shortest* ones. Every shipped word is attested in a real
  corpus: obscure two-letter entries that could never be a useful suggestion are gone, and common
  longer words the length cut-off used to exclude are present. A smaller list therefore suggests
  better than the larger one it replaces.
- The candidate row offers four words instead of three. "helo" has six neighbours at edit
  distance 1, and at three chips the intended word fell just off the end.
- CI now runs `:ui-wear` unit tests and fails if a word list exceeds 10 000 entries, loses its
  frequency column, has a stale compiled index, or packages an index compressed rather than stored.
  The heap overrun and the ranking defect were both invisible to every existing gate.

### Removed
- **SymSpellKt.** Used up to v0.3.0 and removed on measurement, not on preference — see above. The
  keyboard now has no third-party dependency for autocorrect, and the APK contains no MIT-licensed
  component at all. The symmetric-delete algorithm it implements is still the basis of the
  replacement and is credited in `THIRD_PARTY_LICENSES.md`.
- The plain-text word lists no longer ship inside the APK. They are build inputs, kept in
  `dictionaries/` at the repository root and compiled into the `.bin` indexes, which saves 320 KB of
  APK and removes a redundant second copy of the same data.

### Added
- Opt-in spatial word prediction: calibrated per-key tap probabilities are held only until Space, Enter, or an explicit candidate selection resolves the word.
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
