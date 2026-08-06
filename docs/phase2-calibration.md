# Phase 2 — touch model calibration on hardware

Device: OnePlus Watch 2 (`OPWWE231`), 466 × 466 px, Wear OS 4 / API 34.
Build: commit `2521bc4`, `app-debug-phase2.apk` (6.04 MB).
Method: synthetic touches via `adb input swipe x y x y 90` (a 90 ms press — see *Harness notes*),
field contents read back from the browser URL bar, key geometry measured from screenshots rather
than assumed.

Spec §7.1 exit criterion: *"Edge-key error rate acceptable in real use."*

## Key geometry, measured from screenshots

Rather than trusting layout arithmetic, key rectangles were recovered by scanning screenshot
scanlines for runs of non-black pixels. This matters: an early run of these tests used estimated
centres, "failed", and the failure was entirely in the test coordinates.

| Row | y | Key centres (x) |
|---|---|---|
| 1 | 293 | q 45, w 86, e 128, r 170, t 211, y 253, u 295, i 336, o 378, p 419 |
| 2 | 337 | a 63, s 97, d 131, f 165, g 198, h 232, j 266, k 300, l 334 |
| 3 | 381 | z 143, x 173, c 202, v 232, b 262, n 292, m 322 |

## Results

### Centre taps — all rows

| Row | Expected | Produced | Result |
|---|---|---|---|
| 1 | `qwertyuiop` | `qwertyuiop` | pass |
| 2 | `asdfghjkl` | `asdfghjkl` | pass |
| 3 | `zxcvbnm` | `zxcvbnm` | pass |

26 of 26 keys correct. The probabilistic model did not regress ordinary typing, which was the
main risk in replacing containment testing.

### Beyond-edge taps — the §7.1 requirement

Spec §7.1 requires outer-key hit regions to extend radially to the physical display edge, turning
clipped corner area into usable target. Probed by tapping progressively further outward from the
edge keys, onto bare glass where nothing is drawn.

| Probe | x positions | Drawn key spans | Produced | Result |
|---|---|---|---|---|
| Right edge (`p`) | 419, 430, 440, 450, 458, 464 | 401–438 | `pppppp` | pass |
| Left edge (`q`) | 45, 30, 18, 6 | 27–64 | `qqqq` | pass |
| Both edges | 14 and 452 | — | `qp` | pass |

The furthest probe, x = 464, is **2 px from the physical screen edge and 26 px beyond the drawn
key**, and still resolves correctly. Under rectangular hit-testing every one of these coordinates
is outside the key.

### Vertical boundary — row separation

Column x = 205 lies between `t` (211, 293) and `g` (198, 337). Walking down the column:

| y | 293 | 300 | 306 | 312 | 318 | 324 | 337 |
|---|---|---|---|---|---|---|---|
| produced | t | t | t | t | g | g | g |

The switch happens between y = 312 and y = 318. The geometric midpoint is 315, so the decision
boundary sits within ~3 px of centre — no meaningful vertical bias from the radial correction.

A single tap at exactly y = 315 returns `g`. That is a genuine coin-flip position and the downward
component of the outward radial correction breaks the tie toward the lower row. Correct behaviour,
recorded so it is not later mistaken for drift.

## Budget gates

| Gate | Threshold (spec §14) | Measured | Status |
|---|---|---|---|
| Java heap, 1 resident dictionary | < 8 MB | **2.46–2.56 MB** | pass |
| APK size | ≤ 15 MB | **6.04 MB** | pass |
| No arm64 libraries | 0 entries | **0** | pass |
| Frame time, 95th percentile | ≥ 95% under 16.6 ms | see below | not yet proven |

**Frame timing is not yet a clean result.** `dumpsys gfxinfo` reports 95th percentile 26 ms over
20,494 cumulative frames, but that sample spans the whole process lifetime including cold start,
window creation and layout inflation. The modern "Janky frames" counter reads **0.45%**, while the
legacy counter reads 44.2% — the legacy metric is known to misattribute on this class of device.
GPU percentiles are 4/7/7/8 ms, comfortably inside budget, which suggests the CPU-side outliers are
startup rather than steady-state.

A clean measurement needs `KeyGridView`'s own `startFrameTiming()` / `frameStats()` instrumentation
sampled during sustained typing. Until then this gate is **unproven, not passed**.

## Constants — still uncalibrated

`TouchModel.Config` defaults remain the original estimates:

| Constant | Value | Status |
|---|---|---|
| `sigmaXFraction` | 0.55 | plausible, not measured |
| `sigmaYFraction` | 0.42 | supported indirectly — the vertical boundary sits where geometry says it should |
| `maxRadialDriftPx` | 6 px | **not yet justified by data** |
| `driftExponent` | 2.0 | **not yet justified by data** |

The tests above pass comfortably, which means they do not constrain the drift constants: every
probe was far enough from a boundary that ±6 px changes nothing. Establishing real values requires
*human* taps — a person aiming at a named key while the raw coordinate is logged — because the
effect being corrected is finger-pad geometry and wrist angle, which synthetic events do not have.

That is deliberately left open. The honest position: the model is verified **correct and
non-regressive**, and the radial extension demonstrably works; the drift magnitude is an
unvalidated default that currently does no harm.

## Harness notes — for whoever runs these next

1. **`input tap` is unreliable for this UI.** Its synthetic DOWN/UP can arrive in one motion batch,
   too fast for the suggestion strip's press/release hit-test, so taps appear to do nothing. Use
   `input swipe x y x y 90` — identical start and end, explicit 90–120 ms duration.
2. **Measure key positions from a screenshot; do not estimate them.** Two "failures" during this
   session were wrong test coordinates, not defects. The pixel-scan approach takes seconds and is
   exact.
3. **`uiautomator dump` does not include the IME window.** It captures the host app only, so the
   field text is readable but key positions are not.
4. **The watch sleeps aggressively** and `KEYCODE_POWER` *toggles* — pressing it while awake puts
   the device to sleep. Check `mWakefulness` before sending it.
