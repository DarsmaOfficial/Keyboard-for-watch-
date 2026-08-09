# Motion specification

Required by spec §8: *"Deliver a short motion spec document: what animates, duration, easing,
rationale."*

This document records what moves on the keyboard surface, what drives it, and — as importantly —
what deliberately does not move. Every entry is implemented; nothing here is aspirational.

## Principles

**1. Motion confirms, it does not decorate.** A watch keyboard is used in three-second bursts,
often while walking. Animation exists to answer "did that register?" faster than the eye can
verify the letter appeared. Anything that does not answer that question is cut.

**2. Physics, not tweens.** Spec §8 requires spring physics rather than linear interpolation.
A spring's velocity is continuous through an interruption, so a fast typist pressing the next key
mid-release never sees a jump — the animation retargets from wherever it currently is. Duration-
based tweens cannot do this without visible discontinuity.

**3. Never animate what the user is aiming at.** Key *positions* are frozen while a field is
focused. Only appearance changes. This is not an aesthetic choice: an earlier build let the
candidate row collapse when empty, which shifted every key row down by 26 dp mid-word, and typing
"helo" reliably produced "del" because the next tap landed on a chip. Layout stability outranks
every visual effect in this document.

**4. No hidden animation work.** Springs and panel transitions settle and stop, and every callback
is removed when its view detaches. The visible composition caret deliberately toggles every 500 ms;
there is no animator or callback activity after the keyboard is hidden.

## What animates

### Key press — spring scale

| Property | Value |
|---|---|
| Animated property | uniform scale about the key's own centre |
| At rest | `1.0` |
| Fully pressed | `0.88` |
| Driver | `androidx.dynamicanimation` `SpringAnimation` (31 KB, Apache-2.0) |
| Damping ratio | `DAMPING_RATIO_MEDIUM_BOUNCY` |
| Stiffness | `STIFFNESS_HIGH` |
| Settling time | within the 80 ms budget of spec §8 |

**Rationale.** `MEDIUM_BOUNCY` overshoots slightly past 1.0 on release, which reads as the key
springing back rather than deflating. `STIFFNESS_HIGH` keeps the whole gesture inside 80 ms, so
the animation never lags the character appearing in the composition strip — a key that is still
animating after its letter has arrived feels broken.

Scale rather than colour is the primary press signal because it survives being viewed at a glance,
in sunlight, and by users with reduced colour discrimination.

**Cost control.** Only the pressed key is drawn scaled — `canvas.scale` is applied about that key's
centre while the rest of the grid draws unchanged. Untouched keys cost nothing per frame, so a
press does not scale with keyboard size.

### Caret — blink

| Property | Value |
|---|---|
| Period | 500 ms on, 500 ms off |
| Easing | none — a hard toggle |
| Scope | composition strip only |

**Rationale.** A caret is a state indicator, not a transition. Fading it would make "is the caret
here?" ambiguous at exactly the moment the user is checking. The 500 ms period matches the
platform text-cursor convention, so it looks native rather than novel.

### Clipboard / emoji panel — fixed-slot reveal

| Property | Value |
|---|---|
| Animated property | destination alpha `0.55→1` and scale `0.96→1` |
| Duration | 90 ms |
| Geometry | unchanged; all surfaces share one fixed `FrameLayout` slot |
| Input ownership | destination only, immediately |
| Disabled when | system animation is off or power saver is active |

The source surface is hidden before the destination fades in, so invisible content cannot retain
touch input. The transition never moves or resizes keys.

### Composition strip — horizontal auto-scroll

The strip scrolls to keep the caret visible as text exceeds its width. This tracks caret position
directly rather than animating toward it: during fast typing an eased scroll lands where the caret
*was*, which is worse than no animation at all.

## What deliberately does not animate

| Not animated | Why |
|---|---|
| Key positions while a field is focused | See principle 3 — this caused a real typing bug |
| Candidate row appearing / disappearing | Its height is permanently reserved (`INVISIBLE`, never `GONE`) so chips can appear without moving anything |
| Language / layer switches | The grid is redrawn in place. A morph between QWERTY and ЙЦУКЕН, or letters and symbols, would animate 30+ glyphs simultaneously on a Snapdragon W5 for no informational gain — the user already knows they pressed the switch key |
| Keyboard show / hide | Owned by the platform IME window animation; overriding it would desynchronise from the host app's own transition |
| Suggestion chip content changes | Chips update in place. Animating text substitution draws the eye to the row exactly when attention belongs on the composed text |

## Reduced motion

`ValueAnimator.areAnimatorsEnabled()` is checked before starting motion. When the system disables
animation, the spring and panel transition are skipped and their visual states jump directly to the
correct endpoint. Non-essential panel motion is also disabled while Android power saver is active.
This uses the public platform policy API rather than reading `Settings.Global` directly.

## Haptics

Motion has a tactile counterpart. The device exposes `aw-haptic-hv` with `AMPLITUDE_CONTROL` only
and `hapticChannelMaxAmplitude = 0.0`, so there is no primitive or composition support and
`VibrationEffect.createOneShot(duration, amplitude)` is the only usable API (spec §2, §8.1).

| Interaction | Duration | Amplitude (0–255) | Character |
|---|---|---|---|
| Alphanumeric key tap | 8 ms | 120 (~47%) | crisp, low-latency confirmation |
| Spacebar / layer shift | 10 ms | 60 (~23%) | soft transition |
| Enter / primary action | 12 ms | 180 (~70%) | firm execution |
| Backspace / delete | 16 ms | 255 (100%) | high-priority warning |
| Caret scrub step | 4 ms | 40 (~15%) | light tick per character |

Amplitude encodes consequence: deletion is the only irreversible action on the keyboard, so it is
the only one at full amplitude. Caret scrubbing is the lightest because it fires once per character
during a drag and would otherwise become noise.

User-facing intensity setting including full off, per §8.1.

## Verification

- **Frame time.** Spec gate is ≥ 95% of frames under 16.6 ms. `KeyGridView` carries its own
  `startFrameTiming()` / `frameStats()` instrumentation; `dumpsys gfxinfo` mixes in cold-start
  frames and must not be read as a steady-state result.
- **Idle cost.** `dumpsys batterystats` must show no measurable wake attributable to the IME while
  it is hidden.
- **Reduced motion.** Set animator duration scale to 0 and confirm presses and panel switches still
  produce their correct endpoint immediately, with no travel or fade.
