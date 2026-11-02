# v0.4.6 Alpha Signing Solution (R04)

## Signing identities

| Build variant | Signing key | Cert SHA-256 | Upgrade over public v0.4.4-alpha |
|---------------|-------------|--------------|----------------------------------|
| Public v0.4.4-alpha | key A | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | — |
| Local v0.4.6 prod debug | key A | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | ✅ `adb install -r` compatible |
| Local v0.4.6 devtest debug | key A | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | Different package ID (`tw.personal.privateinbox.devtest`) |

## Build command (prod debug, key A)

```bash
./gradlew :app:assembleProdDebug \
  -Pdebug.storeFile="${ANDROID_USER_HOME}/debug.keystore" \
  -Pdebug.storePassword="${DEBUG_STORE_PASSWORD}" \
  -Pdebug.keyAlias="androiddebugkey" \
  -Pdebug.keyPassword="${DEBUG_KEY_PASSWORD}"
```

`ANDROID_USER_HOME` resolves to `$HOME/.android-home` by default via `scripts/_env.sh`. Use the absolute path to the key A keystore if it lives elsewhere.

## What must match for `adb install -r`

Android accepts an in-place upgrade when **all** of the following hold:
1. Same **Application ID** (`tw.personal.privateinbox`)
2. Same **signing certificate** (key A: `c876051e...`)
3. Version code is **greater than or equal to** the installed version

The APK **SHA-256 does NOT need to match** the old APK. Only the signing identity and package ID must match.

## Upgrade path verified

Public v0.4.4-alpha APK → local v0.4.6 prod APK via `adb install -r` succeeds with data preserved, on emulator-5554 (API 36).

## Limitations

- **Debug signing only** — no production release keystore exists
- Cannot be called a production release
- Debug `DebugSettingsProvider` is present (debug builds only, guarded by signature-level permission)
- `PERMISSION POST_NOTIFICATIONS` is present (used for self-test only)
