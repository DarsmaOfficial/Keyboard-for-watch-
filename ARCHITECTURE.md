# Architecture

## The problem, precisely

On Wear OS the keyboard covers the text field. You cannot see what you are typing, so you cannot
tell whether a mistake happened, and fixing one means guessing. Everything below is downstream
of solving that.

Two mechanisms address it, in order of importance:

1. **The keyboard does not fill the screen.** `KeyboardSurfaceView.onMeasure()` constrains the
   IME window to 66% of the display height. The system then resizes or pans the target app into
   the space above, so the app's *real* field stays on screen and updates as you type.
2. **A composition strip mirrors the text** directly above the keys, with a caret. This is the
   fallback for apps that cannot be resized, and it adds caret control (tap to position, drag to
   scrub) that a tiny field would not otherwise give you.

Mechanism 1 is the fix. Mechanism 2 alone is not sufficient — an early version had the strip but
still filled the screen, which reproduced the original problem with extra steps.

## Modules

```
:ime-core     pure Kotlin/JVM — no Android imports, unit-testable without a device
:dict         pure Kotlin/JVM — SymSpell autocorrect engine, unit-testable without a device
:ui-wear      the keyboard surface — View + Canvas only, zero androidx.compose.*
:app          InputMethodService, LAUNCH_KEYBOARD activity, manifest
```

## Dictionary size — measured, not assumed

The single most expensive decision in the project. Numbers below are `dumpsys meminfo`
`Dalvik Heap → Alloc` on the actual watch, from a cold process:

| State | Dalvik heap allocated |
|---|---|
| No dictionary resident | 2.3 MB |
| One resident dictionary, 30 000 words | **39.8 MB** |
| One resident dictionary, 10 000 words | see `CHANGELOG` for the current measurement |

The spec's gate (§14) is **under 8 MB**, and its §4.2 budget assumed 10 000 words per language at
roughly 3.7 MB. An earlier revision shipped 30 000 words without reconciling that against the
budget, and blew the gate by nearly five times: SymSpell's delete-variant table grows worse than
linearly, so tripling the word count cost roughly ten times the heap.

Two measurement traps cost real time here, recorded so they are not repeated:

- **Read `Dalvik Heap → Alloc`, not `App Summary → Java Heap`.** The summary line folds in
  `Dalvik Other` and the shared ART boot-image mapping, so it reported ~47 MB for a process
  *without* any dictionary — higher than one with a dictionary loaded. It cannot be compared
  against the spec's gate at all.
- **`am kill` does not restart the active IME.** The framework recreates `WearKeyImeService`
  almost immediately, so a "baseline" taken that way has already loaded a word list.
  `am force-stop` gives a genuinely cold process, but it also makes the framework treat the IME as
  uninstalled — it must be `ime enable`d again afterwards, with a few seconds' delay before the
  call is accepted.

Which 10 000 words matters as much as how many. The list keeps the most *frequent* words, ranked
from the Leipzig corpora, not the *shortest* ones. Every shipped word is corpus-attested, so
obscure two-letter entries that could never be a useful suggestion are gone while common longer
words the old length cut-off excluded are now present.

### `:ime-core`

**`EditorState`** is the single source of truth for composed text, caret position and the
composing region. It mirrors the subset of `InputConnection` semantics the keyboard needs
(`commitText`, `setComposingText`, `deleteSurroundingText`, `backspace`, `setCaret`) and notifies
listeners synchronously on every mutation.

The critical rule: **never query `InputConnection` per frame.** Every such call is IPC to another
process and would wreck frame timing. Key input mutates `EditorState` first — synchronously, in
process — and the same mutation is then forwarded to the real `InputConnection` wrapped in
`beginBatchEdit()` / `endBatchEdit()`. Reconciliation in the other direction (the app edits its
own field) happens only in `onUpdateSelection`; the keyboard defers to the app rather than
fighting it.

Masked fields are handled inside `EditorState` itself: when `masked` is true, plaintext is never
stored, not even transiently. Only bullet characters are kept, generated from a count, so the
preview strip has something to render and the caret still works.

**`ClipboardStore`** holds local clipboard history: newest first, deduplicated by moving repeats
to the front, capped at 25 unpinned entries. Pinned entries are exempt from both eviction and
expiry. Entries matching OTP or card-number heuristics are marked sensitive and disappear after
two minutes.

### `:ui-wear`

Rendering is a custom `View` drawing on a `Canvas`. This was a deliberate choice over Compose:

| | Artifact weight | Track record on Wear |
|---|---|---|
| Compose (`ui` + `foundation` + `runtime`) | ~11.7 MB pre-R8 | no shipping IME precedent |
| `View` + `Canvas` | ~0.03 MB | what every shipping IME uses |

Against a 15 MB APK budget the first row leaves almost nothing for anything else, and a key grid
is a fixed set of rectangles with press states — exactly what `Canvas` is good at. Circular
clipping and radial hit-zone work are also easier drawn directly than fought through a layout
system. CI fails the build if `androidx.compose.*` appears in this module's dependency tree.

**Round-display geometry.** A rectangular grid on a circular screen loses its outer keys to the
bezel. Each row is therefore fitted to the horizontal chord of the display circle at that row's
height:

```
halfWidth(dy) = sqrt(r² - dy²)
```

Two details that cost real debugging time:

- The circle's centre must be derived from `getLocationOnScreen()`, not `displayMetrics`.
  `displayMetrics` describes the IME window; using it placed keys past the physical glass.
- The function row measures its chord at its *top* edge. Measuring at the bottom, where the
  circle narrows fastest, collapsed the row to an unusable sliver.

Hit-testing is deliberately more forgiving than the drawn rectangles: a tap landing in a gap or
just outside a row's chord resolves to the nearest key centre within the same row.

**`KeyboardSurfaceView`** is the single shared surface — a vertical `LinearLayout` holding the
composition strip above the key grid, with the clipboard panel occupying the grid's slot when
open. Both entry points instantiate exactly this, which is what makes "no forked UI" structural
rather than a convention that erodes.

### `:app`

Wear OS has **two** text-entry paths, and implementing only the first makes the keyboard
invisible in most real flows:

1. `WearKeyImeService` — the classic `InputMethodService`, used by ordinary `EditText` fields.
2. `LaunchKeyboardActivity` — declares
   `com.google.android.wearable.action.LAUNCH_KEYBOARD`. Notification replies, WhatsApp and
   browser URL bars route through `RemoteInputActivity`, which launches an activity declaring
   that intent, never the IME service. Verified on hardware: the system's keyboard chooser lists
   WearKey alongside Gboard.

Language switching uses the real `InputMethodSubtype` mechanism so the OS language picker and
per-field subtype memory keep working, with `switchToNextInputMethod(true)` — `true` matters,
because `false` cycles across *all* installed keyboards and silently hands control to Gboard.
When the framework declines to cycle (it registers only the subtype matching the system locale
unless the user enables more), a local layout swap runs instead, so the key is never dead.

## Things the device does not have

Verified on the target hardware; specifications written for phone Android assume these exist.

- **No rotary input.** No crown, no bezel encoder. Design zero rotary interactions.
- **No autofill framework.** `cmd autofill` reports no such service. No inline suggestions, no
  password-manager chips — there is nothing to integrate with.
- **Haptics are amplitude-only.** No primitives or compositions; use
  `VibrationEffect.createOneShot`.
- **No root, ever.** Bootloader locked, verity enforcing. Shizuku provides shell-level access and
  that is the ceiling.
- **`armeabi-v7a` only.** 32-bit. This is why the project ships no native code at all.
