# Releasing

## The signing key

WearKey release builds are signed with a self-signed RSA-4096 certificate. Android APKs are
self-signed by design — there is no certificate authority involved and none is needed, which is
also why this costs nothing (spec §3.1 forbids any paid component, including paid code signing).

| Property | Value |
|---|---|
| Container | PKCS12 (JKS is deprecated; the `.jks` extension is historical) |
| Key | RSA 4096-bit |
| Signature | SHA-384 with RSA |
| Alias | `key` |
| Subject | `CN=DarsmaOfficial, OU=WearKey, O=DarsmaOfficial, C=AZ` |
| Valid until | 2053-12-21 |

The long validity is deliberate. Android requires a certificate to outlive the app's expected
lifetime, because **an APK signed with a different key cannot upgrade one signed with this key** —
the installer refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. There is no recovery from a lost
key other than a new package name and a fresh install for every user.

## Where the key lives, and where it must not

The keystore is at `/var/minis/shared/wear-keyboard/secrets/wearkey-release.jks`, mode `600`,
**outside the git working tree**. `.gitignore` already covers `*.jks`, `*.keystore` and
`keystore.properties`, but that is a second line of defence, not the first — the file is not in the
repository directory at all, so an `git add -A` cannot reach it.

**Never** commit the keystore, its password, or a base64 copy of either. If one is ever pasted into
a chat, an issue, or a CI log, treat the key as compromised: generate a new one, and accept that
existing installs can no longer be upgraded in place.

## Building a signed release

```sh
/var/minis/shared/wear-keyboard/build-release.sh
```

The script prompts for the password with echo disabled and passes everything to Gradle through the
environment. Nothing is written to a properties file, so nothing can be committed by accident.

To supply the password non-interactively (CI, or a scripted build):

```sh
WEARKEY_KEYSTORE_PASSWORD='…' /var/minis/shared/wear-keyboard/build-release.sh
```

`app/build.gradle.kts` reads four variables:

| Variable | Purpose |
|---|---|
| `WEARKEY_KEYSTORE` | Absolute path to the keystore |
| `WEARKEY_KEYSTORE_PASSWORD` | Store password |
| `WEARKEY_KEY_ALIAS` | Key alias — `key` |
| `WEARKEY_KEY_PASSWORD` | Key password (same as the store password here) |

If `WEARKEY_KEYSTORE` is unset the release build type stays unsigned, which is the normal case for
a local debug build. If it *is* set, every other variable becomes mandatory and a missing one fails
the build with a named error. That asymmetry is intentional: a partially configured signing setup
used to produce an unsigned APK that looked perfectly normal until it would not install over the
previous version.

## Verifying a build before publishing

```sh
# 1. Confirm it is signed, and by the expected certificate
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# 2. Confirm the §3 hard gates still hold
unzip -l app-release.apk | grep lib/           # must be empty — no native code
unzip -p app-release.apk AndroidManifest.xml | strings | grep -c INTERNET      # must be 0
unzip -p app-release.apk AndroidManifest.xml | strings | grep -c RECORD_AUDIO  # must be 0

# 3. Confirm dictionaries are still stored uncompressed, or mmap breaks
unzip -lv app-release.apk | grep dictionaries/   # method must read "Stored"
```

Match the certificate fingerprint against a previous release before publishing. A changed
fingerprint means the wrong key was used and the build must not ship.

## Distribution

Side-loading only. Spec §3.1 excludes Google Play (the US$25 registration fee is a cost) and
deliberately does not target F-Droid. Build a plain APK — never an `.aab`.

## If the key is lost

There is no recovery. Document it honestly rather than pretending otherwise:

1. Generate a new keystore.
2. Change the `applicationId`, because the old package can never be upgraded again.
3. Tell users they must uninstall the old version and install the new one, losing the clipboard
   history and calibration stored under the old package.

Back up the keystore and its password separately, offline, before the first public release.
