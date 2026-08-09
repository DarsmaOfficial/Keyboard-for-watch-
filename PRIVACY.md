# Privacy

A keyboard handles private text, so this page describes what WearKey can access and what it keeps.
The claims below are also checkable in the source and built APK.

## Network and microphone

WearKey does not request `android.permission.INTERNET` or `RECORD_AUDIO`. It cannot open a network
connection and has no voice-input path. There is no analytics SDK, crash-reporting service,
advertising, cloud correction, sync or remote backup.

You can inspect the packaged permissions with Android build tools, for example:

```sh
aapt2 dump permissions wearkey.apk
```

`android.permission.VIBRATE` is declared for key haptics. It is a normal local hardware permission
and does not provide access to personal data.

## Data kept on the watch

| Data | Storage | Removal |
|---|---|---|
| Clipboard history, up to 25 entries | App-private encrypted storage | Clear it from the clipboard panel or uninstall WearKey |
| Theme, language, haptic level and touch calibration | App-private preferences | Use the relevant reset where available, or uninstall WearKey |
| Emoji recents | App-private storage | Use **Clear all data** in WearKey settings or uninstall WearKey |

WearKey does not maintain a learned-word history or usage log. Its English and Russian dictionaries
are fixed files included in the APK. Autocorrect, glide recognition and spatial prediction run
locally against those files.

## Password and PIN fields

For password, visible-password, web-password and numeric-PIN fields, WearKey's local editor state
stores one bullet per character rather than the plaintext. The real character is sent directly to
the app that owns the field.

WearKey also disables suggestions and learning-related behavior for masked fields and fields marked
`IME_FLAG_NO_PERSONALIZED_LEARNING`.

## Clipboard handling

Android only lets an active keyboard read the system clipboard while it has input focus. WearKey
reads it when a field opens; it has no background polling service and no AccessibilityService.
Clipboard entries that resemble a short one-time code or card number expire after two minutes
unless pinned.

## Logs and backups

Keyboard input is not written to Logcat in either debug or release builds. CI rejects logging calls
in the typing source paths.

`android:allowBackup="false"` excludes WearKey's app data from Android backup and device-transfer
backup mechanisms.

## Distribution

WearKey is distributed as an APK from this repository. It has no store account, installer service
or phone companion. Copying the APK to another watch does not contact this project.

If this page and the code disagree, please treat it as a bug and open an issue without including any
private text.
