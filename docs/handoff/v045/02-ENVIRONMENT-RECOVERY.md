# v0.4.5 Environment Recovery

## Verified toolchain (re-checked during independent review, 2026-10-24)

| Component | Path / Value | State |
|---|---|---|
| JDK | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` (17.0.20.1, aarch64) | OK |
| Android SDK | `/System/Volumes/Data/Users/huang/Desktop/我的已讀不回助理/android-sdk` | OK |
| Platforms | android-33, android-36, android-36.1 | OK |
| Build tools | 30.0.3, 33.0.0, 35.0.0, 36.0.0 | OK |
| adb | `$ANDROID_SDK_ROOT/platform-tools/adb` 1.0.41 (37.0.1) | OK |
| emulator | `$ANDROID_SDK_ROOT/emulator/emulator` 37.2.12.0 | OK |
| AVD | `test36` at `$HOME/Desktop/我的已讀不回助理/.android-home/avd/test36.avd` | OK |
| Gradle | 8.13 via `scripts/_env.sh` (wrapper dists pre-extracted) | OK |
| GRADLE_USER_HOME | `<repo>/../gradle_home` (writable) | OK |

## The two original failures and their real causes

1. `gradle-8.13-bin.zip.lck: Operation not permitted`
   The wrapper writes its lock inside `~/.gradle`; that path is outside the
   sandbox's writable roots. `scripts/_env.sh` repoints `GRADLE_USER_HOME` to
   `<repo>/../gradle_home`, which is writable, and the lock problem disappears.

2. `libnative-platform.dylib failed to load for Mac OS X aarch64`
   Raised when the *bare* `gradle` launcher in the wrapper dists directory was
   run directly (a mismatched native-platform jar from an older attempt).
   Running through `./gradlew` with `_env.sh` sourced does not reproduce it.

## Canonical invocation

```bash
cd <repo>
source scripts/_env.sh        # sets JAVA_HOME, GRADLE_USER_HOME, ANDROID_SDK_ROOT, PATH
./gradlew --no-daemon <task>
```

**Never** rely on the ambient shell for `JAVA_HOME`: `apksigner` and `aapt2`
from build-tools need it too, and an unset `JAVA_HOME` produces the misleading
message `Unable to locate a Java Runtime`.

## Task names actually present (from `:app:tasks --all`)

Use the flavour-qualified names; the unqualified `testDebugUnitTest` /
`assembleDebug` from the old AGENTS.md snippet do not exist:

```
:app:testProdDebugUnitTest        :app:testDevtestDebugUnitTest
:app:lintProdDebug                :app:lintDevtestDebug
:app:assembleProdDebug            :app:assembleDevtestDebug
:app:assembleDevtestDebugAndroidTest
:app:connectedDevtestDebugAndroidTest
```

## Emulator

The AVD is not in the default `~/.android/avd`, so `emulator -list-avds` is
empty unless `ANDROID_AVD_HOME` is set:

```bash
export ANDROID_AVD_HOME="$HOME/Desktop/我的已讀不回助理/.android-home/avd"
emulator -avd test36 -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect
adb wait-for-device   # then poll sys.boot_completed == 1 (~60 s cold boot)
```

`scripts/_env.sh` already exports `ANDROID_USER_HOME` pointing at the same tree.

## Reinstall discipline (learned the hard way during P0 verification)

`assembleDevtestDebugAndroidTest` rebuilds **only the test APK**. A main-source
change stays undelivered if the app APK is not rebuilt and reinstalled too, and
the resulting test run silently measures the OLD product code. Always:

```bash
./gradlew --no-daemon :app:assembleDevtestDebug :app:assembleDevtestDebugAndroidTest
adb install -r app/build/outputs/apk/devtest/debug/app-devtest-debug.apk
adb install -r app/build/outputs/apk/androidTest/devtest/debug/app-devtest-debug-androidTest.apk
```

When the source of truth matters, force a real rebuild with `--rerun-tasks`;
UP-TO-DATE tasks produce byte-identical APKs but do not prove freshness against
a rebuilt tree.

## Direct instrumentation invocation (used throughout this review)

```bash
adb shell am instrument -w \
  [-e class <fqcn>[#method][,<fqcn>...]] \
  tw.personal.privateinbox.devtest.test/androidx.test.runner.AndroidJUnitRunner
```

Note: this path does **not** write Gradle's JUnit XML under
`app/build/outputs/androidTest-results/`. For XML you must run
`:app:connectedDevtestDebugAndroidTest`, or parse the runner's per-class dot
lines. This review verified counts from the runner output (25 classes /
139 tests), not from a stale XML file.

## Verdict
GATE_B_ENVIRONMENT = PASS
