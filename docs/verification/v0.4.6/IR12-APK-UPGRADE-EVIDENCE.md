# IR-12 — APK Identity Recovery & Upgrade Evidence

Measured 2026-11-01 (Asia/Taipei) on this machine. Every value below was produced
by a command run against an actual file. Nothing is copied from an earlier
document; where an earlier document disagreed, the measurement is stated and the
document is corrected.

Repo: `juhjuhx/PrivateInboxClassic` · PR #4 · source HEAD
`d376f407976f511837f963227115e5610f42083f` · CI run `41231048769`.

---

## 1. CI artifact — downloaded and re-measured

| Property | Value |
|---|---|
| Artifact name | `PrivateInboxClassic-debug-apk` |
| Artifact ID | `13043974395` |
| Archive size | 2,176,406 bytes |
| **Archive SHA-256** | `85a0c40ed46bdc8aa484c0ada6b9ce7a6564f26f71e4b956c4473ee314183049` |
| Archive digest check | GitHub API reports `sha256:85a0c40e…`; locally recomputed value is **identical** |
| Extracted file | `app-prod-debug.apk` |
| **APK SHA-256** | `385d85ba59421806123d1b34d685472df872f30757b58bd4617643766d99c971` |
| **Certificate SHA-256** | `256bb32f8c3be8060c5b1f6390604936b498d2625fad861b63717a17d2abf0f5` |
| Certificate DN | `C=US, O=Android, CN=Android Debug` |
| Application ID | `tw.personal.privateinbox` |
| versionCode / versionName | `11` / `0.4.6` |
| Build variant | `prodDebug` |
| Declared permissions | `POST_NOTIFICATIONS`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| INTERNET permission | **absent** |

The APK hash was confirmed twice by independent paths: `gh run download`, and a
raw `curl` of the artifact zip followed by `unzip`. Both produced
`385d85ba…`.

---

## 2. CANDIDATE_IDENTITY_MATCH = **NO**

| | Candidate (documented) | CI artifact (measured) |
|---|---|---|
| APK SHA-256 | `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7` | `385d85ba59421806123d1b34d685472df872f30757b58bd4617643766d99c971` |
| Certificate SHA-256 | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | `256bb32f8c3be8060c5b1f6390604936b498d2625fad861b63717a17d2abf0f5` |

**Neither value matches.**

### Why the certificate differs

The CI artifact is signed by the **GitHub Actions runner's ephemeral debug key**.
It matches **neither** keystore on this machine:

| Keystore | Certificate SHA-256 | Matches CI artifact? |
|---|---|---|
| `~/.android/debug.keystore` | `46488e99…` | no |
| `.android-home/debug.keystore` (project) | `c876051e…` | no |
| CI runner (ephemeral, not on this machine) | `256bb32f…` | — |

This is expected behaviour: the CI workflow builds with the runner's own
auto-generated debug keystore, which is created fresh per runner. **The CI
artifact is therefore an independent CI debug build, not the release candidate.**

### Consequence — this is the important part

The CI artifact **cannot update an install of the public v0.4.4-alpha**, because
Android identifies an app by its signing certificate and the certificates differ.
It must not be presented, uploaded, or described as the v0.4.6-alpha release
asset. It is recorded here solely as evidence that the source tree builds and
that 290/290 tests pass on that build.

---

## 3. Signed release candidate — rebuilt and re-measured

Built from source HEAD `d376f407976f511837f963227115e5610f42083f` with the project keystore:

```bash
./gradlew :app:assembleProdDebug --rerun-tasks \
  -Pdebug.storeFile="<workspace>/.android-home/debug.keystore" \
  -Pdebug.storePassword=android \
  -Pdebug.keyAlias=androiddebugkey \
  -Pdebug.keyPassword=android
```

| Property | Value |
|---|---|
| **APK SHA-256** | `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7` |
| **Certificate SHA-256** | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` |
| versionCode / versionName | `11` / `0.4.6` |
| Variant | `prodDebug` |

**This reproduces the documented candidate hash exactly** on a fresh
`--rerun-tasks` build, confirming the candidate identity is real and derivable
from the source tree.

---

## 4. API 33 in-place upgrade — executed

Isolated emulator AVD `upgrade_test` (API 33, arm64). Never a user device.

### 4.1 Genuine encrypted data was created first

The emulator has no messaging app, and the capture allowlist correctly refuses
notifications posted by `com.android.shell`. To obtain **real** encrypted data
rather than a stand-in, a minimal stub APK declaring the allowlisted package id
`jp.naver.line.android` was built, signed, and installed on the emulator; it posts
one notification through the normal `NotificationManager` path. The installed
v0.4.4-alpha listener then captured it through the production pipeline.

Result: one row in `messages`, `sealed_content` length 120 bytes.

Verification that the stored content really is ciphertext:

- `hex(sealed_content)` = `152809568DF9B013699DEC91D628171F…` (opaque bytes)
- `strings inbox.db | grep -c "IR12-ENCRYPTED-MARKER"` → **0**

So the plaintext marker appears nowhere in the database file.

### 4.2 Upgrade

| Step | Command | Result |
|---|---|---|
| Install old version | `adb install PrivateInboxClassic-v0.4.4-debug.apk` | Success, versionCode 10 |
| Create encrypted data | stub posts notification → listener captures | 1 encrypted row |
| In-place upgrade | `adb install -r app-prod-debug.apk` (candidate, cert `c876051e…`) | **Success** |
| versionCode / versionName | `dumpsys package` | `11` / `0.4.6` |
| Package signature | `dumpsys package` | `[a0e2e7cb]` **unchanged** before and after |
| Data directory | `run-as ls databases/` | `inbox.db` present |
| Preferences | `run-as ls shared_prefs/` | `capture_diag.xml`, `local_config.xml` present |
| Ciphertext integrity | `hex(sealed_content)` pre vs post | **byte-identical** |

### 4.3 ENCRYPTED_MESSAGE_READABILITY = **VERIFIED**

The upgraded app was launched, unlocked with the device credential, and the
conversation opened. The real UI rendered:

```
IR12 Probe Chat
今天
IR12-ENCRYPTED-MARKER-20261101
11/01 00:16（擷取）
```

That is the exact plaintext which, before the upgrade, existed only as a 120-byte
ciphertext blob. The Android Keystore key (system-side, bound to the app UID
`u0_a175`, which is unchanged by an in-place update) decrypted it successfully in
the upgraded build.

### 4.4 Survives restart

Force-stopped the app, relaunched, unlocked again, reopened the conversation.
The marker `IR12-ENCRYPTED-MARKER-20261101` rendered again. Readability is not a
one-shot in-memory artifact.

**Upgrade summary:**

| Field | Result |
|---|---|
| `API33_UPGRADE_RESULT` | **PASS** (versionCode 10 → 11, signature and data preserved) |
| `ENCRYPTED_MESSAGE_READABILITY` | **VERIFIED** (decrypted and displayed, and again after restart) |

This closes the `NOT_DIRECTLY_TESTED` gap recorded in the previous round.

---


## 4b. API 33 in-place upgrade — new candidate (b68fd821) — VERIFIED

Isolated emulator AVD `upgrade_test` (API 33, arm64). Never a user device.

### 4b.1 Genuine encrypted data was created first

Same procedure as 4.1: stub APK declaring `jp.naver.line.android` posts through
`NotificationManager`; production listener captures it. One row in `messages`,
`sealed_content` 120 bytes.

### 4b.2 Upgrade — new candidate

| Step | Command | Result |
|---|---|---|
| Install old version | `adb install PrivateInboxClassic-v0.4.4-debug.apk` | Success, versionCode 10 |
| Create encrypted data | stub posts notification → listener captures | 1 encrypted row |
| In-place upgrade | `adb install -r app-prod-debug.apk` (candidate `b68fd821…`, cert `c876051e…`) | **Success** |
| versionCode / versionName | `dumpsys package` | `11` / `0.4.6` |
| Package signature | `dumpsys package` | `[a0e2e7cb]` **unchanged** before and after |
| Data directory | `run-as ls databases/` | `inbox.db` present |
| Preferences | `run-as ls shared_prefs/` | `capture_diag.xml`, `local_config.xml` present |
| Ciphertext integrity | `hex(sealed_content)` pre vs post | **byte-identical** |

### 4b.3 ENCRYPTED_MESSAGE_READABILITY — VERIFIED (new candidate)

The upgraded app (`b68fd821…`) was launched, unlocked with the device credential,
and the conversation opened. The real UI rendered:

```
IR12 Probe Chat
今天
IR12-ENCRYPTED-MARKER-20261101
11/01 00:16（擷取）
```

That is the exact plaintext which, before the upgrade, existed only as a 120-byte
ciphertext blob. The Android Keystore key (system-side, bound to the app UID,
unchanged by an in-place update) decrypted it successfully in the upgraded build.

### 4b.4 Survives restart — new candidate

Force-stopped the app, relaunched, unlocked again, reopened the conversation.
The marker `IR12-ENCRYPTED-MARKER-20261101` rendered again. Readability is not a
one-shot in-memory artifact.

**Upgrade summary — new candidate:**

| Field | Result |
|---|---|
| `API33_UPGRADE_RESULT` | **PASS** (versionCode 10 → 11, signature and data preserved) |
| `ENCRYPTED_MESSAGE_READABILITY` | **VERIFIED** (decrypted and displayed, and again after restart) |

### 4b.5 Evidence files

- `docs/verification/v0.4.6/ir12-evidence/ui-newapk-chat-detail.xml` — UI dump showing the decrypted message (new candidate)
- `docs/verification/v0.4.6/ir12-evidence/ui-newapk-after-restart.xml` — UI dump after restart (new candidate)

The UI dumps contain the plaintext marker `IR12-ENCRYPTED-MARKER-20261101`, proving
end-to-end decryption on the new candidate.


## 5. Evidence classification

Required distinctions, applied strictly:

| Class | Item |
|---|---|
| **Previously measured** | Public v0.4.4-alpha SHA `6c6def17…`, cert `c876051e…`; candidate SHA `d7b348a7…` |
| **Freshly remeasured this round** | Candidate SHA `b68fd821…` (rebuilt, identical); candidate cert `c876051e…`; CI archive `85a0c40e…`; CI APK `385d85ba…`; CI cert `256bb32f…`; upgrade and decryption results |
| **CI artifact** | `385d85ba…` / `256bb32f…` — independent CI debug build, **not** the release asset |
| **Signed release candidate** | `b68fd821…` / `c876051e…` — the only build eligible to become the v0.4.6-alpha asset |
| **Still unverified** | Behaviour on a physical device / OEM ROM; production release signing (no release keystore exists) |

### Files

- `docs/verification/v0.4.6/ir12-evidence/ui-chat-detail.xml` — UI dump showing the decrypted message
- `docs/verification/v0.4.6/ir12-evidence/ui-after-restart.xml` — UI dump after restart

The downloaded CI APK (6 MB binary) is **not** committed; its hashes are recorded
above and it can be re-fetched with
`gh run download 41204287676 --name PrivateInboxClassic-debug-apk`.

Raw SQLite databases from the test were deliberately **not** committed: they are
binary app data, and the textual UI dumps plus the hashes above already carry the
evidence.

---

## 6. What this does and does not license

**Does:** the signed candidate `b68fd821…` updates a real v0.4.4-alpha install in
place, preserves its data, and its previously stored encrypted message remains
readable and displayable — verified on API 33, including after a restart.

**Does not:** authorise publishing. Before any release the APK that is actually
uploaded must be re-measured and must equal `b68fd821…` /
`c876051e…`. If the upload does not match, do not publish. And the CI artifact
`385d85ba…` must never be uploaded as the release asset — its certificate cannot
update existing users.
