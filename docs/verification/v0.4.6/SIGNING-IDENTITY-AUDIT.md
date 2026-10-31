# Signing Identity Audit — measured, not quoted (R04 final)

Every value below was produced by a command run against an actual file on
2026-10-30 (Asia/Taipei). No value is copied from an earlier document.
Where an earlier document disagreed with the measurement, the measurement wins
and the document is corrected in this same commit.

Commands used:

```bash
shasum -a 256 <apk>
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --print-certs <apk>
$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2 dump badging <apk>
keytool -list -v -keystore <keystore> -storepass android
```

---

## 1. The authoritative reference: the published v0.4.4-alpha APK

Downloaded from the public GitHub Release (`v0.4.4-alpha`), file
`PrivateInboxClassic-v0.4.4-debug.apk`.

| Property | Measured value |
|---|---|
| SHA-256 | `6c6def174902a192cf5f65846261c517ff42edff62bde8ee9c72104ddd8c1556` |
| Certificate SHA-256 | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` |
| Certificate DN | `CN=Android Debug, O=Android, C=US` |
| Application ID | `tw.personal.privateinbox` |
| versionCode | `10` |
| versionName | `0.4.4` |

**This certificate is the signing identity that existing users hold.** Any APK
that is to update an existing install in place MUST carry this exact
certificate. "Both are debug builds" is NOT sufficient — a debug certificate is
per-keystore, not universal.

---

## 2. Keystores present on this machine

| Keystore | Certificate SHA-256 | Matches public APK? |
|---|---|---|
| `~/.android/debug.keystore` (Android default) | `46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94` | **NO** |
| `.android-home/debug.keystore` (this project) | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | **YES** |

`.android-home/debug.keystore` is present, readable, and git-ignored
(`.gitignore:25`). It is the only keystore on this machine whose certificate
matches the published release.

### Label correction

`docs/SIGNING.md` §2 labels `~/.android/debug.keystore` as "A" and
`.android-home/debug.keystore` as "B", then concludes "本專案採用金鑰 B".
`docs/releases/v0.4.6-ALPHA-CLOSURE.md` calls the *matching* key "key A". The
letters were used inconsistently between the two documents.

**This audit drops the letter labels entirely.** Identity is the full
certificate SHA-256. The keystore that matters is the one whose certificate is
`c876051e…`, and that is `.android-home/debug.keystore`.

---

## 3. The release candidate built for this release

Built from source HEAD `79f2942059a815d275403d924be1c1e25024d786`:

```bash
./gradlew :app:assembleProdDebug --rerun-tasks \
  -Pdebug.storeFile="<workspace>/.android-home/debug.keystore" \
  -Pdebug.storePassword=android \
  -Pdebug.keyAlias=androiddebugkey \
  -Pdebug.keyPassword=android
```

Signing material is passed as `-P` flags only. **No keystore, password or key
is written into the repository, the build output, or this document.**

| Property | Measured value |
|---|---|
| File | `PrivateInboxClassic-v0.4.6-alpha-debug.apk` |
| SHA-256 | `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67` |
| Certificate SHA-256 | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` |
| Certificate DN | `CN=Android Debug, O=Android, C=US` |
| Application ID | `tw.personal.privateinbox` |
| versionCode | `11` |
| versionName | `0.4.6` |
| Build variant | `prodDebug` |
| Source HEAD | `79f2942059a815d275403d924be1c1e25024d786` |
| INTERNET permission | **absent** (grep count 0) |
| Declared permissions | `POST_NOTIFICATIONS`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| Debug provider | present (`tw.personal.privateinbox.debugsettings`), signature-permission guarded |

### Reproducibility

`./gradlew :app:assembleProdDebug --rerun-tasks` with the same `-P` flags was run
twice from a clean task graph. Both builds produced
`d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67` — the hash is
reproducible from this source tree, so the published value can be re-derived by
anyone with the keystore.

### SIGNING_MATCH = YES

The release candidate's certificate is byte-identical to the published
v0.4.4-alpha certificate.

> **Earlier documentation was wrong about the hash.** `ALPHA-CLOSURE.md` and
> `RELEASE-BASELINE-TRUTH.md` recorded SHA-256
> `a817434edefcb8f5becec48b4ab46ba91badc80bed2b294c0355247c1d6a653c` for the
> v0.4.6 APK. No file with that hash exists in this build. A build from the same
> source with the default keystore produced `e051fec4…` (cert `46488e99…`,
> which would NOT update an existing install); a build with the matching
> keystore produces `d7b348a7…` (cert `c876051e…`). The published hash was from
> a build that is not reproducible from this tree and is superseded by the
> measured value above.

---

## 4. Data-preserving upgrade — actually executed

Performed on an isolated emulator (AVD `upgrade_test`, API 33, arm64), never on
a user device. No uninstall, no data clear, no signature change.

| Step | Command | Result |
|---|---|---|
| 1. Install public v0.4.4-alpha | `adb install PrivateInboxClassic-v0.4.4-debug.apk` | `Success`; versionCode 10 |
| 2. Create verifiable app data | `run-as … echo upgrade-probe-v046-A > files/upgrade-marker.txt` | marker written; `shared_prefs/capture_diag.xml` present |
| 3. Confirm data readable before upgrade | `run-as … cat files/upgrade-marker.txt` | `upgrade-probe-v046-A` |
| 4. In-place upgrade | `adb install -r PrivateInboxClassic-v0.4.6-alpha-debug.apk` | `Success` |
| 5. versionCode / versionName | `dumpsys package` | `11` / `0.4.6` |
| 6. Data still present | `run-as … cat files/upgrade-marker.txt` | `upgrade-probe-v046-A` — **preserved** |
| 7. Prefs still present | `run-as … ls shared_prefs/` | `capture_diag.xml` — **preserved** |
| 8. Signature unchanged | `dumpsys package … signatures=` | `[a0e2e7cb]` before **and** after |
| 9. New version launches | `am start …/.MainActivity` + logcat | process running, **no FATAL / no crash** |

### DATA_PRESERVING_UPGRADE = VERIFIED

Both APKs used in the test:

- old: `6c6def174902a192cf5f65846261c517ff42edff62bde8ee9c72104ddd8c1556`
  cert `c876051e…`
- new: `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67`
  cert `c876051e…`

---

## 5. Scope and limitations of this verification

Stated so the claim is not read as broader than the evidence:

1. **The upgrade test moved a marker file and the SharedPreferences file, not a
   populated message database.** The emulator has no messaging package
   installed, so the capture whitelist correctly refused to store the synthetic
   notification and no encrypted rows were produced. What is proven is that the
   package manager accepts the new APK over the old one, that the app data
   directory and prefs survive the replace, and that the new build starts
   cleanly. **Encrypted-row survival across the upgrade is inferred from the
   unchanged data directory, not directly observed.**
2. This is emulator evidence only. No physical device, no OEM build.
3. The debug certificate is a debug certificate. This is a Debug Alpha, not a
   production-signed release. No release keystore exists in this project.
4. `assembleProdRelease` produces an **UNSIGNED** APK (see §6). The Alpha asset
   is the signed `prodDebug` build, which is the correct choice for a Debug
   Alpha and is stated as such in the release notes.

---

## 6. Correction: what `assembleRelease` actually does

`docs/SIGNING.md` §3.3 previously claimed that when no release keystore is
configured, `assembleRelease` signs the APK with the **debug** key. That is not
what the build does, and it never was in this version.

Measured:

```
$ ./gradlew :app:assembleProdRelease
SIGNING: no release keystore configured. The release build will be
         UNSIGNED and is NOT distributable. See docs/SIGNING.md.

$ apksigner verify app/build/outputs/apk/prod/release/app-prod-release-unsigned.apk
DOES NOT VERIFY
ERROR: Missing META-INF/MANIFEST.MF
```

The Gradle message says `UNSIGNED and is NOT distributable`
(`app/build.gradle.kts:205`), and the artifact is genuinely unsigned — it has no
`META-INF/MANIFEST.MF` at all. `SIGNING.md` has been corrected accordingly.

This matters because the old text would have let someone believe an unsigned
release build was a debug-signed, installable artifact.

---

## 7. Mandatory re-verification before publication

The values above were measured from a build produced on 2026-10-30. The APK
artifact is **not committed to the repository** (it is a build output), so the
file that is ultimately uploaded must be re-measured at upload time:

```bash
shasum -a 256 PrivateInboxClassic-v0.4.6-alpha-debug.apk
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --print-certs \
  PrivateInboxClassic-v0.4.6-alpha-debug.apk
```

Publish only if the two values are exactly:

- SHA-256 `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67`
- Certificate SHA-256 `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`

If either differs, do **not** publish: rebuild from code HEAD `79f2942` with
`.android-home/debug.keystore` and re-measure, or investigate why the artifact
changed. A mismatched upload is the one failure this whole audit exists to
prevent.
