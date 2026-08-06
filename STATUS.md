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
| Frame time, 95th percentile | ≥ 95% under 16.6 ms | see §"Unproven" | ❌ **not proven** |
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
| Installable language packs (§4.3) | ❌ not started |

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
| `:engine-swipe` | DTW path matching | ❌ not started |

**Test coverage: 99 unit tests across four modules**, all passing in CI.

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

## Remaining work

### Unproven — measurement, not code

1. **Frame time (§14).** `dumpsys gfxinfo` reports 95th percentile 26 ms, but that is cumulative
   over the whole process lifetime including cold start. The modern jank counter says **0.45%**
   while the legacy counter says **44.2%**, and GPU percentiles (4/7/7/8 ms) are comfortable — which
   points at startup outliers rather than steady-state cost. A clean number needs `KeyGridView`'s
   own `startFrameTiming()` / `frameStats()` sampled during sustained typing.
2. **Cold IME show < 150 ms (§14).** Never measured.
3. **Context matrix (§14).** Verified in the browser URL bar only. The spec requires notification
   reply, WhatsApp, plain `EditText`, password field, number field and search field — these exercise
   genuinely different code paths.
4. **Text entry rate and error rate (§14).** Requires the MacKenzie & Soukoreff phrase set, plus a
   Gboard baseline for comparison. The spec is explicit that improvement claims must be relative to
   measured data.
5. **Battery (§11.5).** `dumpsys batterystats` idle cost never checked.

### Resolved since the first draft of this document

- **TalkBack virtual view hierarchy (§11.5)** — ✅ **built.** Each key is now an
  `AccessibilityNodeInfo` node in deliberate traversal order. Touch exploration reads keys without
  typing them; activation needs an explicit double-tap arriving as `ACTION_CLICK`. Written against
  the framework rather than `ExploreByTouchHelper`, whose POM pulls `androidx.core` (§12). Key
  activation was extracted into one path shared by finger and screen reader, so the two cannot
  drift. *Not yet verified with TalkBack running on the watch.*
- **Frame-time instrumentation (§14)** — ✅ **built.** `onDraw` is timed directly into a fixed
  4096-sample buffer, read from a settings screen. The previous implementation called `invalidate()`
  every frame, which measured a synthetic 60 fps loop rather than the keyboard's real cost and burnt
  battery doing it. *Numbers still need a real typing session.*
- **State survival across process death (§11.5)** — ✅ **already satisfied; no code needed.** Every
  keystroke is committed to the `InputConnection` immediately, so the authoritative text lives in
  the host app's field and `onStartInputView` restores it via `getExtractedText`. Persisting typed
  text to our own storage would have added a place for it to leak while recovering data that was
  never at risk. Four tests pin the invariant so a future change cannot silently invalidate the
  argument.

### Not started — code

1. **Swipe / glide typing (§7.3), `:engine-swipe`.** The largest single remaining item. Now
   unblocked: DTW scoring needs exactly the per-key probability distribution the touch model already
   exposes.
2. **Spatial prediction, eyes-free mode (§7.2b).** R&D, explicitly optional, never default.
3. **Emoji layer and themes (§11 v0.3).**
4. **Installable language packs (§4.3).** Each requires an individual licence audit — roughly half
   of common European languages are copyleft and cannot ship.
5. **Instrumented IME lifecycle smoke test (§9).** Zero `androidTest` sources exist.
6. **First-run tutorial (§11.5).**
7. **Release signing + reproducible offline build (§13, §3.1).** No release keystore yet, and the
   "builds offline from a clean machine" claim needs dependency locking and checksums to be a fact
   rather than an aspiration.

---

## Suggested order

1. **Frame timing instrumentation** — small, self-contained, closes the last Phase 2/3 gate.
2. **TalkBack node hierarchy** — the most significant correctness gap, and accessibility work gets
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
