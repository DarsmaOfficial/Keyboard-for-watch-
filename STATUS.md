# WearKey — build status against the specification

Snapshot of what is built, what is verified on hardware, and what remains. Written against the
master prompt (spec §-numbers below refer to it).

**Commit:** `2724801` · **Repository:** [DarsmaOfficial/Keyboard-for-watch-](https://github.com/DarsmaOfficial/Keyboard-for-watch-)
· **Device:** OnePlus Watch 2 `OPWWE231`, Wear OS 4 / API 34, 466 × 466

A deliberate distinction runs through this document:

- **Verified** — exercised on the physical watch and observed to work.
- **Built** — code exists and CI passes, but it has not been confirmed on-device.
- **Not started** — no implementation.

Anything not actually checked is marked *unproven*, even where it is likely fine. A gate that was
never measured is not a gate that passed.

---

## Budget gates (spec §14)

| Gate | Threshold | Measured | Status |
|---|---|---|---|
| Java heap, 1 resident dictionary | < 8 MB | **2.46 – 2.92 MB** | ✅ verified |
| APK size | ≤ 15 MB | **6.10 MB** | ✅ verified |
| No arm64 libraries | 0 | **0** | ✅ verified |
| No `androidx.compose.*` in keyboard path | 0 | **0** | ✅ CI-enforced |
| Permissions declared | 0 | **0** — not even `INTERNET` | ✅ verified |
| Composed text always visible | 100% of fields | verified in browser URL bar | ⚠️ partial — full context matrix pending |
| Frame time, 95th percentile | ≥ 95% under 16.6 ms | **p95 = 2.51 ms, 0.39% over budget** (259 frames) | ✅ verified |
| Cold IME show | < 150 ms | never measured | ❌ **not measured** |
| Text entry rate | ≥ 15 WPM | never measured | ❌ **not measured** |
| Battery vs Gboard | no regression | never measured | ❌ **not measured** |

---

## v0.1 MVP (spec §11)

| # | Requirement | Status |
|---|---|---|
| 1 | IME installs, enables, is selectable | ✅ verified |
| 2 | **Live composition strip with caret** — the headline feature | ✅ verified |
| 3 | Round-optimised QWERTY, tap input | ✅ verified |
| 4 | English + Russian layouts with switching | ✅ verified |
| 5 | Backspace, space, enter, shift, symbol layer | ✅ verified |
| 6 | Amplitude haptics per §8.1 | ✅ built — exact duration/amplitude map implemented |

Both Wear OS entry points (§4.5) are implemented and were confirmed on the device: the
`InputMethodService` **and** the `LAUNCH_KEYBOARD` Activity. The second is the most commonly missed
Wear requirement; without it the keyboard is invisible in most reply flows.

---

## v0.2 (spec §11)

| Requirement | Status |
|---|---|
| Clipboard manager, encrypted at rest (§6) | ✅ built, 13 unit tests |
| SymSpell-style autocorrect (§7.2) | ✅ verified both languages |
| Installable language packs (§4.3) | ⚠️ built, 6 tests — not verified on-device |

Autocorrect was verified end to end on the watch in both languages: `helo` → **hello / help / held
/ hero**, and `привт` → **привет / приют**, with tap-to-accept committing the word and clearing the
candidate row.

**Note on the dictionary engine.** The spec named SymSpellKt as a direct dependency, and §4.2 asked
for it to be benchmarked before assuming mmap savings. It was measured, and it did not fit: 10 000
words consumed **15.5 MB** of Dalvik heap against the 8 MB gate, because the delete table allocates
three Java objects per variant. Spec §4.2 pre-authorised the fallback verbatim — *"write a small
flat-trie reader with a genuine mmap path"* — so SymSpellKt was removed and replaced with a binary
index mapped directly from the APK. Heap fell to ~2.5 MB. This is a spec-sanctioned substitution,
not a deviation.

---

## Phase 2 — circular touch model (spec §7.1)

**Status: complete and verified.**

Rectangular hit-testing was replaced with a bivariate Gaussian model over key centroids, in
`:ime-core` as pure Kotlin so it is unit-testable without a device.

Verified on hardware:

| Check | Result |
|---|---|
| Centre taps, all three rows | **26/26 correct** |
| Beyond right edge (`p`) | correct out to **x = 464** — 2 px from the screen edge, 26 px past the key |
| Beyond left edge (`q`) | correct in to **x = 6** |
| Vertical row boundary | switches within **~3 px** of geometric midpoint — no bias |

One derivation worth recording: the Gaussian normaliser is **deliberately omitted**. Including it
penalises physically large keys — a tap just inside the spacebar's end would be stolen by the narrow
key beside it, because a wide key spreads its density thinner. Under the correct area-proportional
prior, `P(k) ∝ σx·σy` cancels the normaliser exactly, so the unnormalised form *is* the posterior.
A regression test using the real function-row geometry pins this.

`bestMatch()` accepts an optional per-key log-prior and `distribution()` exposes the full normalised
posterior. Neither is needed for tap input — they are the seam §7.2b spatial prediction plugs into.

### On-device calibration

Spec §7.1 asks for an *empirical* offset vector μ(x, y, r). The shipped constants were estimates, so
a calibration screen was added: 25 targets on a golden-angle spiral, fitting `e = A·u^k` by least
squares in log space.

Pipeline verified by injecting a **known** 8.0 px drift with exponent 2.0 — the fitter recovered
7.05 px / 1.75 and reported mean miss **3.1 px → 0.3 px (92% better)**.

⚠️ **The constants on the device are still the defaults.** The synthetic fit was deliberately
cleared, because `adb input` takes integer coordinates only and cannot reproduce finger-pad
geometry. Real values need a human session — one minute, via *Settings → Калибровка касаний*.

---

## Architecture (spec §9)

| Module | Purpose | Status |
|---|---|---|
| `:ime-core` | EditorState, caret, touch model, calibration | ✅ 8 files, 42 tests |
| `:layout-engine` | Declarative JSON layouts | ✅ 2 files, 13 tests |
| `:ui-wear` | Key grid, composition strip, motion — View + Canvas | ✅ 7 files |
| `:dict` | mmap dictionary reader | ✅ 5 files, 31 tests |
| `:app` | IME host, settings, calibration | ✅ 9 files |
| `:clipboard` | separate module | ⚠️ implemented inside `:ime-core`/`:app` instead |
| `:engine-swipe` | DTW path matching | ⚠️ built, 9 tests — not verified on-device |

**Test coverage: 114 unit tests across six modules**, all passing in CI.

`:app` unit tests were added to the CI task list at the same time as the language-pack tests — they had been committed but never executed, since the task list named only four modules. A test that does not run is worse than none, because it reads as coverage.

`:clipboard` is a deliberate deviation: the store is pure logic (in `:ime-core`, 13 tests) and the
encryption is Android Keystore (in `:app`). A third module would have added a Gradle boundary
without separating anything that is not already separated.

---

## Legal deliverables (spec §3.2)

| File | Status |
|---|---|
| `LICENSE` (Apache-2.0) | ✅ |
| `NOTICE` (§4(d) attributions) | ✅ |
| `THIRD_PARTY_LICENSES.md` | ✅ |
| `README.md`, `PRIVACY.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `ARCHITECTURE.md` | ✅ |
| `MOTION.md` (§8 motion spec) | ✅ |
| In-app offline licences screen | ✅ built |
| en + ru strings (§5.5) | ✅ both locales, all modules |

Everything in the APK is permanently free: Apache-2.0 code, BSD/SCOWL word lists, CC-BY frequency
data, and one Apache-2.0 library (`androidx.dynamicanimation`, 31 KB). No accounts, no tiers, no
telemetry. The absence of `INTERNET` makes the offline claim kernel-enforced rather than a promise.

---

## Frame time — measured (spec §14)

Sampled by timing `onDraw` directly during a real typing session: letters, suggestion updates,
symbol-layer switches and backspaces.

| Metric | Value |
|---|---|
| Frames sampled | 259 |
| Median | **1.8 ms** |
| p90 / p95 / p99 | 2.19 / **2.51** / 2.84 ms |
| Worst | 66.03 ms — one frame, the first draw |
| Over 16.6 ms | **0.39%** |
| **Gate (≥ 95% under 16.6 ms)** | ✅ **PASS** |

p95 sits about 6.6× inside budget. This also resolves the earlier three-way contradiction from
`dumpsys gfxinfo`: its 26 ms p95 was cold-start contamination of a lifetime-cumulative counter, its
legacy jank figure of 44.2% was simply wrong for this device, and its modern 0.45% counter was
honest — it agrees with the 0.39% measured here.

Getting this number required a fix, not just a run. The measurement existed but was unreachable:
pressing *start* in Settings called through to the live key grid, which is necessarily null because
an IME and a settings screen are never on screen together. The request is now latched so the next
keyboard to appear begins recording, and percentiles are snapshotted when the keyboard is dismissed
— otherwise leaving the field to read them is what destroys the view holding them.

---

## Remaining work

### Unproven — measurement, not code

1. **Cold IME show < 150 ms (§14).** Never measured.
3. **Context matrix (§14).** Verified in the browser URL bar only. The spec requires notification
   reply, WhatsApp, plain `EditText`, password field, number field and search field — these exercise
   genuinely different code paths.
4. **Text entry rate and error rate (§14).** Requires the MacKenzie & Soukoreff phrase set, plus a
   Gboard baseline for comparison. The spec is explicit that improvement claims must be relative to
   measured data.
5. **Battery (§11.5).** `dumpsys batterystats` idle cost never checked.

### Resolved since the first draft of this document

- **TalkBack virtual view hierarchy (§11.5)** — ⚠️ **built, and demonstrably not yet working.**
  Each key is an `AccessibilityNodeInfo` node in deliberate traversal order, activation arrives as
  `ACTION_CLICK`, and hover routing was added. Written against the framework rather than
  `ExploreByTouchHelper`, whose POM pulls `androidx.core` (§12).

  **Tested with TalkBack actually running, and it failed.** A single tap on `h` — which under touch
  exploration must only announce the key — typed `h` into the field. Repeated across three keys:
  `hqb`. This is exactly the failure §11.5 names, and it makes the keyboard unusable with a screen
  reader, because the only way to identify a key is to type it.

  **The control test was weaker than first claimed, and the correction matters.** It was recorded
  as proving the harness sound: a single `adb input tap` on a settings row moved TalkBack's focus
  ring without activating the row. But the same screenshot shows the row's value had *changed* —
  the tap moved focus **and** activated. So `adb shell input tap` does not reliably reproduce touch
  exploration, and the keyboard result it produced cannot be trusted either way. The bug may be
  real or may be an artefact of injected input bypassing `TouchExplorer`; on the current evidence
  that is genuinely undetermined, and the earlier "definitive" framing was wrong.

  Three fixes attempted so far, each addressing a real defect but none sufficient:
  1. `dispatchHoverEvent` routing — the provider had no hover handling at all, so exploration
     gestures fell through to `onTouchEvent`.
  2. `importantForAccessibility = YES` — a Canvas view with no text resolves AUTO to *not
     important*, which excludes it from the tree entirely.
  3. Removing the `contentDescription` added in (2) — a labelled view is treated as a leaf, so the
     framework stops descending and never asks for the virtual children.

  **`hasChildren=false` was also over-read.** In `AccessibilityWindowInfo` that field reports child
  *windows*, not child nodes — an IME window legitimately has none, so it is not the signal it was
  treated as. `uiautomator dump` returning nothing likewise proves little: it excludes IME windows
  by design, which is already documented in the gotchas below.

  So of the three "diagnostics" used, one was misread, one was inapplicable, and the control was
  invalid. The three code changes are still defensible on their own merits — a provider with no
  hover routing genuinely cannot support exploration, and an AUTO-importance Canvas view genuinely
  is dropped from the tree — but none of them has been *shown* to fix anything.

  **How to test this properly** (needs TalkBack on, so it needs the user's agreement):
  1. Enable TalkBack, then explore with a **real finger**, not `adb input tap`.
  2. Read the node tree with `dumpsys accessibility a11ycache` or an
     `AccessibilityService`-based dump, which unlike `uiautomator` can see IME windows.
  3. The pass condition is that a finger resting on a key announces it and leaves the field
     unchanged; activation happens only on double-tap.
- **Frame-time instrumentation (§14)** — ✅ **built and measured.** `onDraw` is timed directly into
  a fixed 4096-sample buffer, read from a settings screen. The previous implementation called
  `invalidate()` every frame, which measured a synthetic 60 fps loop rather than the keyboard's real
  cost and burnt battery doing it. Real session: **p95 2.51 ms, 0.39% over budget, gate PASS** —
  see the frame-time section above. Reaching it required fixing a lifecycle bug where the request
  was dropped because no keyboard was showing when Settings issued it.
- **State survival across process death (§11.5)** — ✅ **already satisfied; no code needed.** Every
  keystroke is committed to the `InputConnection` immediately, so the authoritative text lives in
  the host app's field and `onStartInputView` restores it via `getExtractedText`. Persisting typed
  text to our own storage would have added a place for it to leak while recovering data that was
  never at risk. Four tests pin the invariant so a future change cannot silently invalidate the
  argument.

### Built this session, awaiting device verification

- **Glide typing (§7.3), `:engine-swipe`** — resampling, banded DTW, frequency-ranked candidates,
  trace capture, trail rendering and commit. 9 unit tests. The gestures in those tests are
  synthetic; whether *real* swipes rank correctly is a device question and is not claimed here.
- **Installable language packs (§4.3)** — signature-checked pack discovery, offline file import,
  Settings screen. 6 unit tests. Each future pack still needs its own licence audit; roughly half
  of common European languages are copyleft and cannot ship.

### Not started — code

1. **Spatial prediction, eyes-free mode (§7.2b).** R&D, explicitly optional, never default.
2. **Emoji layer and themes (§11 v0.3).**
3. **Instrumented IME lifecycle smoke test (§9).** Zero `androidTest` sources exist.
4. **First-run tutorial (§11.5).**
5. **Release signing + reproducible offline build (§13, §3.1).** No release keystore yet, and the
   "builds offline from a clean machine" claim needs dependency locking and checksums to be a fact
   rather than an aspiration.

---

## Suggested order

1. **Frame timing instrumentation** — small, self-contained, closes the last Phase 2/3 gate.
2. **Verify TalkBack on-device** — the node hierarchy is built but has never been driven by a real
   screen reader; enabling TalkBack and exploring the grid is the only way to know it behaves. Was
   the most significant correctness gap, and accessibility work gets
   harder the longer the view grows.
3. **Context matrix** — cheap, and it tests the headline requirement in the places §4.5 says
   actually matter.
4. **Swipe typing** — the big feature, best started once the above are settled.

---

## Working notes for future sessions

Hard-won, each from a mistake that cost real time:

- **`am force-stop` on the IME hands control to Gboard.** The framework treats a force-stopped IME
  as uninstalled and falls back. Re-enable with `ime enable` + `ime set` afterwards.
- **`input tap` is unreliable here.** Its synthetic DOWN/UP can arrive in one motion batch, too fast
  for press/release hit-testing. Use `input swipe x y x y 100`.
- **Measure key positions from a screenshot; never estimate them.** Several apparent "bugs" during
  testing were wrong test coordinates. A pixel scan of a screenshot row takes seconds and is exact.
- **`uiautomator dump` excludes the IME window.** Field text is readable; key positions are not.
- **`KEYCODE_POWER` toggles.** Pressing it while awake puts the watch to sleep. Check
  `mWakefulness` first.
- **CI debug keys are ephemeral** — `adb uninstall` before reinstalling, which also wipes prefs.
- **Read `Dalvik Heap → Alloc`, not `App Summary → Java Heap`.** The summary folds in shared ART
  boot-image mapping and reported ~47 MB for a process with no dictionary loaded at all.
- **The build fails on any logging call in keyboard source.** Intentional: a keyboard must never log
  anything near what the user types. The rule holds only without exceptions.
