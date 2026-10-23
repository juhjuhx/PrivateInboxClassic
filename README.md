# Private Inbox Classic

**An offline, encrypted archive of the notifications your messaging apps
deliver to Android.**

When a message arrives in LINE, Telegram, WhatsApp, Instagram, Messenger or
Threads, Android hands the notification to a listener service. Private Inbox
Classic stores a copy of it, encrypted on the device. If the message is
retracted in the source app, your copy is still there.

It is **not** a chat client, **not** a message relay, and **not** a chat
history backup tool. It reads only what Android's notification system
delivers, and it has no network access at all.

- English: this file
- 繁體中文: [README.zh-TW.md](README.zh-TW.md)

---

## What it does

- **Captures notifications** from a selected, explicit list of messaging apps
- **Encrypts everything at rest** with AES-256-GCM, key in the Android Keystore
- **Groups by conversation**, using the platform's own conversation identity
  rather than the display name
- **Searches locally** by text (conversation title, sender, message body),
  source app and date range
- **Exports** an encrypted, versioned bundle for the offline DHC connector
- **Explains itself**: a diagnostics export with stage codes and timings, and
  never any message content

## What it does not do

| It does not | Why |
|-------------|-----|
| Send, reply to, or relay messages | It is an archive, not a client |
| Read your chat history | It only sees what arrives as a notification |
| Use the network | There is no `INTERNET` permission, and the CI fails the build if one appears |
| Track you | No analytics, no ads, no Firebase, no crash reporting |
| Recover messages that were never notified | If the source app never posted a notification, there is nothing to capture |

---

## Supported platforms

| Source | Status |
|--------|--------|
| LINE | Supported |
| Telegram | Supported |
| WhatsApp | Supported |
| Instagram | Supported |
| Messenger | Supported |
| Threads | Supported |
| Telegram X | Supported |
| Teams | Supported |
| KakaoTalk | Supported |
| Others | Add one entry in `SourceRegistry.kt`. Nothing else changes. |

The list is an **explicit allowlist**, not a global recorder. You choose which
sources are captured.

---

## How notification capture works

```
source app posts a notification
        |
        v
Android delivers it to the listener service
        |
        v
PrivateNotificationListener.onNotificationPosted()   <- runs on the MAIN thread
        |
        |  - snapshot only what must be synchronous
        |  - (the ephemeral media URI is read HERE, because the provider
        |     can revoke the grant the moment the callback returns)
        v
workQueue (bounded, 512)                             <- in-memory
        |
        v
capture-worker                                        <- ONE writer thread
        |
        |  - decode images, normalise text, compute the dedup key
        |  - group several notifications into ONE transaction
        v
InboxDatabase.saveBatches()                          <- one fsync per batch
        |
        v
AES-256-GCM sealed row in inbox.db
```

**The dedup key is a message identity, not a notification identity.** A group
notification is delivered as a growing history, so revision N re-presents every
message it has ever carried. The key includes the conversation identity, the
sender, the content, and a per-message discriminator, so a re-presented message
is recognised as the same message instead of being stored again.

See [`docs/NOTIFICATION-CAPTURE.md`](docs/NOTIFICATION-CAPTURE.md) and
[`docs/reliability/DEDUP-IDENTITY-AUDIT.md`](docs/reliability/DEDUP-IDENTITY-AUDIT.md).

---

## Known Android limitations

These are **platform properties**, not bugs in this app. They are stated here
so that nobody reports them as defects.

| Limit | Detail |
|-------|--------|
| **25 messages per notification** | `Notification.MessagingStyle` carries at most 25 messages. A single revision with more is truncated by the **platform**. No listener can see more. |
| **The app's own notifications are not delivered to it** | Android excludes an app's own notifications from its own listener. |
| **The source decides what to post** | If the source app never posts the message, there is nothing to capture. |
| **Retracted messages are kept** | That is the **purpose**. A retraction in the source app does not remove your copy. |
| **The listener must be granted** | The user grants notification access in the system settings. Revoking it stops capture immediately. |
| **OEM background policies** | Xiaomi, vivo, OPPO, realme, HONOR and Huawei limit background apps. The app shows you where to change the settings; it never changes them for you. |

### Verified on

| Environment | Status |
|-------------|--------|
| Android 16 (API 36), AOSP emulator | **Tested** — 126 instrumentation tests, 84 unit tests |
| Android 9 to 15 | **NOT TESTED** — no device available |
| vivo OriginOS, Xiaomi HyperOS, OPPO ColorOS, realme UI, HONOR MagicOS, Huawei EMUI | **NOT TESTED** — no device available |
| Samsung One UI, Motorola | **NOT TESTED** — no device available |

The app supports API 28 and later, and the code only uses APIs that exist on
API 28. **That is not the same as having tested API 28.** See
[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md).

---

## Privacy and security

| Property | How |
|----------|-----|
| Message content at rest | AES-256-GCM; the key lives in the Android Keystore |
| Message fingerprints | HMAC-SHA256, so low-entropy text cannot be dictionary-attacked |
| Screenshots | `FLAG_SECURE`, unconditional in every build type |
| Diagnostics | Timestamps, stage codes, package names, error **type** names. Never content. |
| Network | There is **no** network permission |
| The Keystore key | It never leaves the device |
| Export | Decrypted with the Keystore key, then re-encrypted with a passphrase-derived key |

Migrations are **additive**. A schema upgrade never deletes or rewrites a row
you would lose.

Details: [`SECURITY.md`](SECURITY.md) and
[`docs/SECURITY.md`](docs/SECURITY.md).

---

## Installation

Download a debug APK and install it, or build it yourself (below).

```bash
adb install -r PrivateInboxClassic-v0.4.4-debug.apk
```

`install -r` **keeps your data**. Do not uninstall: that deletes it.

Then:

1. Open the app and pick the sources you want.
2. Grant notification access in the system settings (the app links to it).
3. On a Chinese-market ROM, allow **auto-start**, **background activity** and
   **lock the app in recents**. See below.

### Per-OEM background settings

These ROMs limit background apps. The app detects the manufacturer and shows
the right path, but **you** make the change.

| ROM | Settings |
|-----|----------|
| Xiaomi / Redmi / POCO (MIUI, HyperOS) | Settings -> Apps -> Manage apps -> Auto-start. Also Settings -> Battery -> Background activity. |
| vivo (OriginOS, Funtouch OS) | Settings -> Battery -> Background power management. Also allow auto-start, and lock the app in recents. |
| OPPO (ColorOS) | Settings -> Battery -> App battery management -> allow the app. Also allow auto-start. |
| realme UI | Same as ColorOS. |
| HONOR (MagicOS) | Settings -> Apps -> App launch -> allow auto-start. |
| Huawei (EMUI, HarmonyOS) | Settings -> Apps -> App launch. Note: the Chinese builds have no Google Play Services, and this app does not need them. |

---

## Building from source

Requirements: **JDK 17**, the **Android SDK 36**, and the Gradle wrapper
(the repository carries it).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"

./gradlew :app:assembleProdDebug
./gradlew :app:testProdDebugUnitTest
./gradlew :app:lintProdDebug
```

The output is `app/build/outputs/apk/prod/debug/app-prod-debug.apk`.

### The two variants

| Variant | Application id | What it is for |
|---------|---------------|----------------|
| `prod` | `tw.personal.privateinbox` | The shipped app |
| `devtest` | `tw.personal.privateinbox.devtest` | The **isolated** test variant. It has its own data directory, so the destructive tests physically cannot reach the production database. |

**Never run a destructive test against the `prod` variant.** The guard refuses
it, and `scripts/audit-test-isolation.py` fails the build if a test bypasses
the guard.

### The audits

```bash
bash scripts/audit.sh                    # manifest, privacy policy, permissions
bash scripts/audit-secrets.sh            # secrets in the tree AND the full history
bash scripts/audit-release.sh            # the built artifacts
python3 scripts/audit-test-isolation.py  # no destructive test on user data
```

Details: [`docs/BUILDING.md`](docs/BUILDING.md) and
[`docs/TESTING.md`](docs/TESTING.md).

---

## Architecture

| Concern | Where it lives |
|---------|----------------|
| The source list | `SourceRegistry.kt` |
| The capture policy | `CapturePolicy.kt` (delegates to the registry) |
| Conversation identity | `ConversationIdentity.kt` |
| Encryption | `CryptoStore.kt` |
| Storage | `InboxDatabase.kt` |
| The listener | `PrivateNotificationListener.kt` |
| Diagnostics | `CaptureDiagnostics.kt` |
| The UI | `MainActivity.kt` and the framework's own views |

**One source of truth per concept.** Adding a platform means one entry in
`SourceRegistry.kt`. Changing the crypto means `CryptoStore.kt`. The UI never
contains a platform check.

The UI uses plain `LinearLayout` / `TextView` with the platform theme, in a
Telegram Classic style. It is not Compose and not Material You.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## SQLite storage

Schema **v5**, additive migrations only.

```sql
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    source_ts INTEGER NOT NULL DEFAULT 0,
    conversation_key TEXT NOT NULL DEFAULT '',
    message_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
    fingerprint BLOB NOT NULL UNIQUE,
    sealed_content BLOB NOT NULL,
    sealed_image BLOB
);
```

`sealed_content` holds the AES-256-GCM sealed JSON payload. `fingerprint` is
the HMAC dedup key, and it is UNIQUE, so the database is the second line of
defence against a duplicate.

**Measured**: 511 bytes per stored message, so 100,000 messages is about
51 MB. The list queries use a covering index.

See [`docs/DATABASE.md`](docs/DATABASE.md) and
[`docs/reliability/STORAGE-BENCHMARK.md`](docs/reliability/STORAGE-BENCHMARK.md).

---

## Testing

| Suite | Count | Command |
|-------|-------|---------|
| Instrumentation (isolated variant) | 126 | `./scripts/g.sh :app:connectedDevtestDebugAndroidTest` |
| Instrumentation (production variant) | 126, destructive ones skip | `./scripts/g.sh :app:connectedProdDebugAndroidTest` |
| Unit | 84 | `./scripts/g.sh :app:testProdDebugUnitTest` |
| DHC connector (Python) | 23 | `python3 integrations/dhc/tests/test_connector.py` |
| Cross-language fixtures | 7 | `DhcPythonFixtureInstrumentedTest` (instrumentation; runs with the suite above) |

The instrumentation tests need an **unlocked** emulator or device: the
Keystore is CE-backed and fails on a locked device.

See [`docs/TESTING.md`](docs/TESTING.md) and
[`docs/TEST-REPORT.md`](docs/TEST-REPORT.md).

---

## The DHC connector

Private Inbox can export an **encrypted, versioned bundle**. This repository
ships a local Python import adapter in [`integrations/dhc/`](integrations/dhc/)
that decrypts the bundle and imports it into a local data store. The broader
DHC workstation ([`hades60414-sys/dhc-cli`](https://github.com/hades60414-sys/dhc-cli),
AGPL-3.0) is **not integrated yet** — it is a separate project, and no DHC source
code is copied into this MIT repository. The two sides exchange a **data
contract**, not source code, and the contract is versioned: an unknown schema
version is **rejected, never guessed**.

The export never hands a message to a network service. It produces a file the
user chooses where to put.

See [`docs/DHC-INTEGRATION.md`](docs/DHC-INTEGRATION.md).

---

## Roadmap

| Item | State |
|------|-------|
| Persistent intake journal | Research. The contract and the measured loss window are written down; it is **not** connected to the capture path. |
| Rust core | Research. The measurements say the current Kotlin is not the bottleneck. |
| Notification mirror | Design. |
| Media capture improvements | Design. |
| Multi-device sync | Design. **No network code exists.** |

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and
[`docs/roadmap/`](docs/roadmap/).

---

## Contributing

Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) and
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) first.

The hard rules are short, and they are not negotiable:

1. No `INTERNET` permission.
2. No tracking, analytics, advertising or network SDKs.
3. No Root, Accessibility Service, or sandbox bypass.
4. Never log message content.
5. Never weaken `FLAG_SECURE`.
6. Never hard-code a key.
7. Migrations are additive.
8. Never claim a test passed unless it was actually run.

**Never include real message content, sender names, or chat screenshots in an
issue, a pull request, or a test fixture.** Synthetic content only.

---

## License

[MIT](LICENSE). See [`docs/OPEN-SOURCE-PROVENANCE.md`](docs/OPEN-SOURCE-PROVENANCE.md)
for the dependency and provenance audit.

---

## Acknowledgements

- The **Android notification listener API**, which makes this possible at all.
- The **Android Keystore**, for keeping the key out of the app's own storage.
- **SQLite**, and the people who maintain it.
- The maintainers of **Kotlin**, **Gradle** and the **Android Gradle Plugin**.
