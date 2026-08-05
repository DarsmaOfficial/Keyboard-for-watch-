# Privacy

A keyboard sees everything you type. You are right to be suspicious of one. This document
states plainly what WearKey does and does not do, in terms you can verify yourself.

## Nothing leaves your watch

WearKey does not request `android.permission.INTERNET`. This is not a policy or a promise — an
Android app without that permission is **incapable** of opening a network connection. Check the
APK yourself:

```sh
unzip -p app-release.apk AndroidManifest.xml | strings | grep -i internet   # no match
```

Consequently there is:

- no telemetry or analytics
- no crash reporting to any server
- no cloud spell-check, translation, or AI features
- no sync, no backup to a remote service
- no advertising or tracking SDKs of any kind

## No microphone

`RECORD_AUDIO` is not requested, there is no voice-input button, and the app does not register
for `android.speech.action.RECOGNIZE_SPEECH`. WearKey is a touch-only keyboard and will remain
one. Verify:

```sh
unzip -p app-release.apk AndroidManifest.xml | strings | grep -i record_audio  # no match
```

## What is stored on the device

| Data | Where | Why | How to erase |
|---|---|---|---|
| Clipboard history (up to 25 entries) | App-private storage on the watch | So you can paste something you copied earlier | "Clear all" in the clipboard panel, or uninstall |
| Selected keyboard layout / language | App-private preferences | To restore your choice | Uninstall |

App-private storage is readable only by this app and by the device owner with root. This watch
has file-based encryption active, so it is encrypted at rest by the platform.

There is **no** learned-word dictionary, typing history, or usage log. Autocorrect is not
implemented yet; when it is, words will never be learned from password, OTP or
`IME_FLAG_NO_PERSONALIZED_LEARNING` fields.

## Password and OTP fields

When the field you are typing into is a password, visible-password, web-password or numeric-PIN
field, WearKey never stores the characters you type — not even briefly in memory for the preview
strip. The strip shows bullet characters, generated from a count, and the real characters go
straight to the app you are typing into.

Text copied from such fields is not captured into clipboard history. Clipboard entries that look
like one-time codes (4–8 digits) or card numbers are flagged and automatically deleted after two
minutes unless you pin them.

## Clipboard access

Android 10 and later only allow a keyboard to read the system clipboard while it is actually on
screen and focused. WearKey works strictly within that boundary: it reads the clipboard when you
open a text field, and never in the background. There is no background service, no polling loop,
and no AccessibilityService workaround.

## No logging of keystrokes

Nothing you type is written to the Android log, at any log level, in any build — including debug
builds.

## Backups

`android:allowBackup="false"` is set, so the app's data is excluded from Android's
backup/transfer mechanisms. Your clipboard history does not travel to a new device or into
anyone's cloud.

## Distribution

WearKey is distributed as an APK file from a public GitHub repository. There is no store, no
account, and no installer that reports back. A copied APK works exactly the same as a downloaded
one.

## Verifying any of this

The complete source is at
<https://github.com/DarsmaOfficial/Keyboard-for-watch-> and builds reproducibly with
`./gradlew :app:assembleDebug`. If something in this document does not match the code, that is a
bug — please open an issue.
