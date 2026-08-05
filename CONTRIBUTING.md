# Contributing

Contributions are welcome. There is no CLA and no copyright assignment — you keep the copyright
on your work, and by opening a pull request you agree it is licensed under Apache-2.0, the same
as the rest of the project.

## Non-negotiable constraints

These are the reason the project exists in this form. A change that breaks one of them will not
be merged, however good it is otherwise.

1. **No `android.permission.INTERNET`.** The app must remain incapable of network access. This
   rules out cloud spell-check, translation, AI features, telemetry and remote crash reporting,
   including as opt-in features.
2. **No `RECORD_AUDIO`, no voice input.** Not now, not as an option later.
3. **Permissive dependencies only** — MIT, Apache-2.0 or BSD, for anything shipped inside the
   APK. GPL/AGPL keyboards may be studied for ideas; do not copy code from them. Every new
   dependency, font, icon set or data file needs its licence stated and recorded in
   `THIRD_PARTY_LICENSES.md` before it lands.
4. **Nothing that costs money**, now or later — no paid services, no free tiers with quotas, no
   accounts, no store dependencies.
5. **No native code (NDK).** The target watch is `armeabi-v7a` only, and a single arm64-only
   transitive dependency would install fine and then crash at runtime. Pure Kotlin/JVM removes
   that failure mode by construction.
6. **No `androidx.compose.*` in `:ui-wear` or `:app`.** The keyboard surface is `View` +
   `Canvas`. CI fails the build if Compose appears in those modules' dependency tree.
7. **Never log keystrokes**, at any level, in any build. CI greps for logging calls in the
   keyboard source and fails if it finds any.

## Before you open a pull request

```sh
./gradlew :ime-core:test        # unit tests must pass
./gradlew :app:assembleDebug    # must build
```

Anything touching `EditorState`, `ClipboardStore` or caret behaviour needs unit tests — those
modules are pure Kotlin precisely so they can be tested without a device.

Anything touching the key grid, the composition strip or window sizing needs a screenshot from a
real round watch. The emulator does not reproduce how the bezel clips keys, and screenshots from
`adb screencap` do not either — a photograph of the physical device is what catches it.

## Style

- Kotlin official style.
- Comments should explain *why*, especially where a decision looks odd. Several parts of this
  codebase encode hard-won device-specific findings; if you change one, say what you learned.
- Keep the keyboard's hot paths allocation-free. It runs every time anyone types anything.

## Reporting bugs

Please include the watch model, Wear OS version, the app you were typing into, and — for
anything visual — a photograph of the watch rather than a screenshot.
