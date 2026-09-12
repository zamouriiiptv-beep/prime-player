# The debug signing key

`castivio-debug.keystore` signs every **debug** build of Castivio, on CI and on a
developer's machine alike.

```
alias      androiddebugkey
store/key  android
SHA-256    73:B5:56:2B:7A:43:E8:93:89:45:33:0E:78:E2:CF:93:
           02:6A:16:5F:FA:F7:19:E0:34:33:80:A5:28:3D:34:C0
SHA-1      A6:49:90:04:17:71:2B:E5:FC:E3:12:BB:6B:DD:0F:38:F2:5A:C4:2A
valid to   2056-09-04
```

## Why it exists

Android will not install an APK over an app already installed under a **different**
signing certificate. The Android Gradle Plugin generates `~/.android/debug.keystore`
when it does not find one, and a GitHub Actions runner is a fresh machine every run
with no such file and nothing caching it — so every CI build was signed with a new,
randomly generated key.

The consequence was not a build failure. It was that each delivered APK refused to
install over the previous one, the only way in was to uninstall, and uninstalling
deletes the app's database: **the provider record and the entire imported catalogue**.
Every build silently wiped the user's subscription and dropped them back at the
activation screen, which read as a data-layer regression and was a signing problem.

Pinning the key makes consecutive builds upgrade in place, which is the only way to
test a change against a catalogue that took four minutes to import.

## Why a key is in the repository

Because this one is a debug key, and a debug key that had to be kept secret would be a
release key in the wrong place. Its password is Android's well-known debug password,
deliberately. What it can do is sign a build that installs over another debug build of
the same `applicationId`; what it cannot do is sign anything anybody would ship.

**Release signing is not configured in this project and must not be added here.**
`RELEASE_CHECKLIST.md` governs that, and a release key belongs in a secret store.

## Using a secret instead

`build.yml` prefers `keystore/ci-debug.keystore` over this file when it exists, and
writes it from the optional `DEBUG_KEYSTORE_BASE64` repository secret. To switch:

```sh
base64 -w0 keystore/castivio-debug.keystore    # or your own debug keystore
# → GitHub → Settings → Secrets and variables → Actions → New repository secret
#   name: DEBUG_KEYSTORE_BASE64
```

Set `DEBUG_STORE_PASSWORD`, `DEBUG_KEY_ALIAS` and `DEBUG_KEY_PASSWORD` in the job's
environment if the supplied keystore does not use the debug defaults. Once the secret
is in place this file can be deleted — builds keep upgrading in place because the
certificate is the one the secret carries.

**Whichever is used, do not change it once devices have the app installed.** Changing
the debug certificate reintroduces exactly the problem this fixes: one forced
uninstall, and the catalogue on every test device is gone.

## Checking two builds match

Every run prints the certificate of the APK it produced, under **Print the APK signing
certificate**. Identical SHA-256 across two runs is the proof that the second APK
installs over the first. Locally:

```sh
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```
