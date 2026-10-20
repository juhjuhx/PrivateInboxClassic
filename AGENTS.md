# AGENTS.md

Guidance for AI coding agents (and humans) working on this repository.

---

## What this project is

Private Inbox Classic is an **offline, privacy-first Android notification
archiver**. It stores copies of messaging notifications (LINE, Telegram,
WhatsApp, Instagram, Messenger, Threads) locally, encrypted, so a message that
is later retracted in the source app can still be read.

**It is not** a chat client, **not** a message relay, and **not** a chat-history
backup tool. It reads only what Android's notification system delivers.

---

## Hard rules

These are not negotiable. A change that violates one of them must be rejected.

1. **Never add `android.permission.INTERNET`.** The app's entire privacy
   claim depends on it. The CI job `Audit the built APK permissions` fails the
   build if it appears.
2. **Never add tracking, analytics or advertising SDKs.** The only allowed
   runtime dependency is `androidx.activity`.
3. **Never add Root, Accessibility Service, or any sandbox bypass.** The app
   must work on a stock, non-rooted device.
4. **Never log message content.** Not to Logcat, not to a crash report, not to
   the diagnostics buffer. Only package names, stage codes, timestamps and
   exception type names.
5. **Never weaken `FLAG_SECURE`.** It is unconditional in every build type.
6. **Never hard-code a key.** All crypto material comes from the Android
   Keystore via `CryptoStore`.
7. **Never delete user data as part of a migration.** Migrations are additive.
8. **Never claim a test passed unless it was actually executed.** Mark
   unexecuted work `NOT TESTED`.

---

## Architecture rules

- **One source of truth per concept.**
  - Platforms → `SourceRegistry.kt`. Never add a platform check in the UI.
  - Crypto → `CryptoStore.kt`.
  - Capture policy → `CapturePolicy.kt` (delegates to `SourceRegistry`).
  - Conversation identity → `ConversationIdentity.kt`.
- **The listener must never crash.** `onNotificationPosted` is wrapped in a
  guard. A listener that throws is killed by the system, which silently stops
  *all* capture. A single malformed notification must not be able to do that.
- **Background work stays off the main thread.** Decoding, database access and
  image work all run on a worker.
- **The notification callback may only do what must happen synchronously.**
  Reading an ephemeral media URI must happen inside the callback (the provider
  can revoke the grant at any time); decoding must not.
- **UI uses the framework's own views.** The Telegram Classic look is
  reproduced with plain `LinearLayout`/`TextView` and the platform theme.
  Do not migrate to Compose, Material You, or any new UI framework.

---

## Build and test

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs an unlocked emulator/device
bash scripts/audit.sh
bash scripts/audit-secrets.sh
```

### Cross-package gotcha (read this before writing instrumentation tests)

The instrumentation APK is a **separate package with its own UID**. It cannot
write the app's `SharedPreferences`. Use the debug-only
`DebugSettingsProvider` (`app/src/debug/`) to configure the app's sources and
to read its capture counters. It does not exist in release builds.

The instrumentation *targets* the app package, so `targetContext` **is** the
app's context and `InboxDatabase(targetContext)` opens the real database.

---

## Where to put things

| Change | File |
|--------|------|
| New platform | `SourceRegistry.kt` — one entry, nothing else |
| New notification source shape | `PrivateNotificationListener.previews()` |
| New classification rule | `SourceRegistry.classify()` + a test |
| Storage change | `InboxDatabase.kt` — add a column and a migration, never a destructive rewrite |
| Crypto change | `CryptoStore.kt` |
| New screen | `MainActivity.kt` |
| New diagnostic event | `CaptureDiagnostics.kt` — add a code, a stage, and a counter |

---

## Documentation rules

- Documentation must match the code. If you change behaviour, update the
  matching document in the same commit.
- Never write a completion report that claims something works without a
  command and its output.
- `docs/TEST-REPORT.md` separates **PASS** / **FAIL** / **NOT TESTED** /
  **BLOCKED**. Keep that separation.

---

## Commit style

Small, focused commits with a message that explains *why*. The repository
history is the audit trail for this project.
