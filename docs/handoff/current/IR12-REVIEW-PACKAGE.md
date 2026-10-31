# Review Package — Private Inbox Classic v0.4.6-alpha

Read-only. Prepared 2026-11-01 for a reviewer who has **not** implemented
IR-09, IR-10 or IR-11.

---

## 1. What you are being asked to review

| | |
|---|---|
| Repository | `juhjuhx/PrivateInboxClassic` |
| PR | #4 — https://github.com/juhjuhx/PrivateInboxClassic/pull/4 |
| Source HEAD | `c6d9b1aed4b730935bea425091afb90847eacdf7` (code HEAD `79f2942`) |
| CI | run [41204287676](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41204287676) — success, **290/290** instrumentation |
| Full diff | https://github.com/juhjuhx/PrivateInboxClassic/compare/3d56a7f5...c6d9b1a |
| Current review state | **REVIEW_PENDING** — 0 formal GitHub reviews |

Nothing is merged and nothing is released. `v0.4.4-alpha` is the only published
release.

### Disclosure

The PR contains review comments written by the **implementing agent**. They are
not independent sign-off and must not be read as approval. GitHub also rejects
self-review (`Can not request changes on your own pull request`), which is why no
formal review exists.

---

## 2. Read these first (in order)

1. `AGENTS.md` — the project's non-negotiable hard rules (no INTERNET, no
   tracking SDKs, no logging of message content, never weaken `FLAG_SECURE`,
   additive migrations only, **never claim a test passed unless it was executed**).
2. `docs/verification/v0.4.6/SIGNING-IDENTITY-AUDIT.md` — measured signing
   identity, keystore comparison, reproducibility, and the CI-artifact finding.
3. `docs/verification/v0.4.6/IR12-APK-UPGRADE-EVIDENCE.md` — the in-place upgrade
   and decryption evidence.
4. `docs/verification/v0.4.6/FINAL-TEST-MATRIX.md` — per-suite results.
5. `docs/verification/v0.4.6/IR-REMEDIATION-EVIDENCE.md` — RED/GREEN per finding.
6. `docs/releases/v0.4.6-ALPHA-CLOSURE.md` — closure state and limitations.
7. `docs/handoff/current/FINAL-V046-HANDOFF.md` — handoff and gate table.

---

## 3. Review scope, in priority order

1. **Conversation identity & cross-conversation isolation** —
   `readConversationMessages()` must fail closed when neither an alias nor a link
   exists; membership is alias ∪ link; the cursor predicate must not escape the
   membership expression.
2. **SQLite migration, merge/unmerge, data preservation** —
   `merge()`/`unmerge()` transactional undo journal covering message links *and*
   alias ownership; cross-package merge refused; cycle guard; `ADD COLUMN`
   idempotence.
3. **Notification capture, dedup, linking** — discriminator derived from source
   timestamp / sender+content, never list index (the 25-message MessagingStyle
   cap shifts indices).
4. **Chat pagination, async callbacks, lock state** — IR-11A/B/C/D specifically:
   conversation state isolation, `(received_at, id)` cursor, fetch/render split,
   request-generation gating, render-only refresh.
5. **Debug provider, permissions, crypto, privacy** — signature-permission guard
   plus `sameSigner()`; no INTERNET permission; `FLAG_SECURE`; no message content
   in logs.
6. **APK signing, upgrade compatibility, release assets** — see §4.
7. **Consistency between PR commits and the verification documents.**

---

## 4. Artifact identity — verify this yourself

| | Value |
|---|---|
| **Signed release candidate** APK SHA-256 | `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67` |
| **Signed release candidate** cert SHA-256 | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` |
| Public v0.4.4-alpha APK SHA-256 | `6c6def174902a192cf5f65846261c517ff42edff62bde8ee9c72104ddd8c1556` |
| Public v0.4.4-alpha cert SHA-256 | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` |

The two certificates are **identical**, which is what makes the upgrade work.

Reproduce the candidate:

```bash
./gradlew :app:assembleProdDebug --rerun-tasks \
  -Pdebug.storeFile="<workspace>/.android-home/debug.keystore" \
  -Pdebug.storePassword=android \
  -Pdebug.keyAlias=androiddebugkey \
  -Pdebug.keyPassword=android

shasum -a 256 app/build/outputs/apk/prod/debug/app-prod-debug.apk
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/prod/debug/app-prod-debug.apk
```

Expected: hash `d7b348a7…`, cert `c876051e…`.

### ⚠ The CI artifact is NOT the release asset

Artifact `13043974395` from run `41204287676` measures:

- APK SHA-256 `385d85ba59421806123d1b34d685472df872f30757b58bd4617643766d99c971`
- cert `256bb32f8c3be8060c5b1f6390604936b498d2625fad861b63717a17d2abf0f5`

That is the GitHub runner's **ephemeral debug key**. It matches neither keystore
on the development machine and **cannot update a v0.4.4-alpha install**. It
exists only as proof that the tree builds and tests pass. Uploading it would
break the upgrade path for existing users.

---

## 5. Upgrade evidence — what was executed

On an isolated API 33 emulator:

1. Installed public v0.4.4-alpha (versionCode 10, cert `c876051e…`).
2. Created **genuine** encrypted data: a minimal stub APK declaring the
   allowlisted package id `jp.naver.line.android` posted one notification through
   the normal `NotificationManager` path; the installed listener captured it via
   the production pipeline. Stored row: `sealed_content` 120 bytes, and the
   plaintext marker appears **nowhere** in the database file.
3. `adb install -r` of the candidate — no uninstall, no data clear.
4. versionCode 10 → 11; package signature `[a0e2e7cb]` unchanged; `inbox.db` and
   `shared_prefs/` preserved; `sealed_content` byte-identical.
5. Launched, unlocked, opened the conversation: the app **decrypted and
   displayed** the marker text.
6. Force-stopped, relaunched, unlocked, reopened: the message rendered again.

So encrypted-message readability after upgrade is **VERIFIED**, not inferred.

---

## 6. Known limitations (stated, not hidden)

- **Emulator only** (API 33). No physical device, no OEM ROM validation.
- **Debug signing only.** No release keystore exists in this project; this is a
  **Debug Alpha**. `assembleProdRelease` produces an **UNSIGNED** APK
  (`apksigner: DOES NOT VERIFY`, missing `META-INF/MANIFEST.MF`) — it is not a
  debug-signed artifact, and `SIGNING.md` was corrected on that point.
- **No independent sign-off yet** — that is why you are reading this.
- Rename changes the legacy key, so a re-delivered message is stored as a new row
  (canonical conversation stays one). Documented, not fixed — fixing it requires
  rewriting stored keys, which is forbidden.
- Messages captured during a merge stay with the target after unmerge (asserted
  contract).
- Process death between capture and commit loses in-flight rows (Persistent
  Intake Journal is DESIGN_ONLY, deferred to v0.4.7).

---

## 7. Publication conditions

Do not publish until **all** hold:

1. A formal GitHub review from a reviewer who did not implement IR-09/10/11.
2. No unresolved P0/P1.
3. CI green on the final source HEAD.
4. The APK **actually uploaded** re-measured and equal to
   `d7b348a7…` / `c876051e…`.
5. Release notes and assets consistent with the measured values.

If the uploaded APK does not match, stop — do not publish.
