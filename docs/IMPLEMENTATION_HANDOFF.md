# Implementation Handoff: Private Inbox Classic v0.2

## Repo Truth
- Android native Kotlin, no app third-party dependencies.
- Package `tw.personal.privateinbox`.
- `CapturePolicy.kt`: hardcoded application whitelist, pure Kotlin policy.
- `LocalSettings.kt`: capture allowed toggle, images toggle and sources; all off by default.
- `PrivateNotificationListener.kt`: captures MessagingStyle / fallback text & photo from notification.
- `ImageCapture.kt`: ephemeral Uri / BigPicture extraction, resolution/size cap, optional EXIF rotation and JPEG conversion.
- `CryptoStore.kt`: Android Keystore AES-GCM + HMAC fingerprint.
- `InboxDatabase.kt`: DB v2 migration preserves v1 rows, encrypted content and photo, conversation display query.
- `MainActivity.kt`: Material One-style chat list/chat/photo/search/settings, credential gating.
- `.github/workflows/android-apk.yml`: build APK and manifest permission gate.

## Verified
- `bash scripts/audit.sh`: XML manifests parsed, disallowed sensitive permissions absent, backup false, notification listener binding declared.
- `bash scripts/test-core.sh`: 8 pure Kotlin checks pass.

## Unverified
- NO Android SDK / Gradle build available in current environment. No APK emitted.
- Android build may expose compiler / Gradle issues; downstream CI must be green before installation.
- No emulator or physical tests. Especially LINE image URI permission behavior is UNKNOWN.

## Next execution
1. Create private GitHub repo, push complete directory; run `Android debug APK` via Actions; obtain Android SDK 36/Gradle CI output.
2. Fix any build regression; inspect output `aapt dump permissions` for absent INTERNET.
3. Instrument fake notifications: MessagingStyle imageUri, BigPicture bitmap/icon, text, null/blocked content, duplicates, group summaries, image-only post.
4. Human test on real device with synthetic non-sensitive notifications only; verify no read receipt / no file leak / no network permission.
5. Fix UI scaling (200% fonts), notification parsing for the user's actual target Apps, DB pagination, bounded retention, backup/export with explicit opt-in if desired.
6. Run independent security audit before producing permanently signed release.
