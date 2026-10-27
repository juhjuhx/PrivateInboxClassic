# v0.4.6 Alpha Signing Solution (R04)

## Problem
Public v0.4.4-alpha uses **key A** (`c876051e...`) → `adb install -r` requires same signing cert for overwrite. Local candidate was built with **key B** (`46488e99...`).

## Solution: Use key A for the public alpha release asset

Build command:
```bash
./gradlew :app:assembleDevtestDebug \
  -Pdebug.storeFile="${ANDROID_USER_HOME}/debug.keystore  (or absolute path to key A keystore)" \
  -Pdebug.storePassword="android" \
  -Pdebug.keyAlias="androiddebugkey" \
  -Pdebug.keyPassword="android"
```

Output: `app/build/outputs/apk/devtest/debug/app-devtest-debug.apk`  
SHA-256: `<verify after build>` (must match public v0.4.4-alpha for `install -r`)

## Upgrade path
From GitHub's v0.4.4-alpha APK to local v0.4.6-alpha APK via `adb install -r` → no uninstall needed, data preserved.

## Next step
After R01 final regression passes (now 239/239), build with key A and verify upgrade path.
