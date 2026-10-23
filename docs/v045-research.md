# v0.4.5 Research — Message Integrity & Multi-Device Reliability

## Source of truth
- Baseline: `87df4ec` (v0.4.4 / code 10), branch `fix/x200pro-notification-burst`
- Worktree: `feature/v045-message-integrity` @ `87df4ec`
- Remote `integration/dsh-v040` is at `6436fec` (Codex has pushed); **not merged into this worktree**

## P0 — Long text / Unicode (findings)

### Truncation points (this app's own limits)
1. `CapturePolicy.kt`: `MAX_BODY_LENGTH = 20_000` + `?.take(MAX_BODY_LENGTH)` — **app-side silent truncation**. Forbidden by the brief. Remove or convert to a resource-limit boundary with explicit failure recording.
2. `PrivateNotificationListener.previews()`: `CapturePolicy.normalize(...)` called on EVERY field before it reaches the worker. So the stored body is already normalized (whitespace collapsed + trimmed + truncated). Breaks the "raw capture" contract.
3. `Notification.EXTRA_TEXT_LINES`: `.takeLast(15)` — app-side truncation of InboxStyle lines.
4. `MAX_PREVIEWS = 500` — safety bound on messages per notification; documented and recorded. Keep, but record dropped count (already does).

### Semantic-altering normalization (must stop)
- `normalize()` does `trim()` + `replace(WHITESPACE_RUNS, " ")` + `take()` + `takeIf { isNotBlank() }`.
- Applied to body, sender, title, text lines, big text — **before** persistence.
- This mutates the original text: leading/trailing whitespace, tab structure, internal runs all change.
- Fix: store raw text; move normalization to a separate **display** layer.

### Unicode risks
- `JSONObject(String(plaintext, Charsets.UTF_8))`: invalid surrogate pairs in the source can throw at parse time. Need a golden corpus test.
- `JSONObject.put("body", body)` → `optString("body")`: round-trips through JSON. Lone surrogates may be replaced by U+FFFD. Need test.
- `CryptoStore.seal()` uses `toByteArray(Charsets.UTF_8)` on JSON string — fine for valid UTF-16 (Android Strings are UTF-16). But the JSON encoding step may alter lone surrogates.
- `fingerprintInput()` uses `toByteArray(Charsets.UTF_8)` — same risk.

### Field priority (current)
```
EXTRA_MESSAGES (MessagingStyle) → body per message
EXTRA_TEXT_LINES (InboxStyle) → one Preview per line (takeLast 15)
EXTRA_BIG_TEXT ?: EXTRA_TEXT → single Preview
```
Missing: `EXTRA_HISTORIC_MESSAGES`, `EXTRA_TITLE`, `EXTRA_SUB_TEXT`, `EXTRA_SUMMARY_TEXT`, `EXTRA_CONVERSATION_TITLE`.

### Content provenance (missing)
No `SOURCE_FIELD`, `CAPTURE_TIME`, `NOTIFICATION_REVISION`, `ORIGINAL_LENGTH`, `STORED_LENGTH`, `CONTENT_COMPLETE`, `PARTIAL_REASON`. Need schema v6 or provenance column.

## P0 — Retraction (findings)

- `onNotificationRemoved` only clears `seenDiscriminators` memory; committed rows stay (good — immutable capture already holds).
- No retraction detection; no `SOURCE_RETRACTION_OBSERVED` marker.
- Removal reasons (`REASON_APP_CANCEL`, etc.) not recorded.
- Need: distinguish `NOTIFICATION_REMOVED` / `SOURCE_CANCELLED` / `REMOVAL_REASON_UNKNOWN`.

## P1 — Source expansion

Current 8 platforms: LINE, Instagram, Messenger, Threads, WhatsApp, Telegram, Telegram X, Teams, KakaoTalk.
Missing per brief: Signal, Discord, WeChat, QQ, enterprise apps.
**Package IDs must be verified** (no guessing). Need trusted source.

## P1 — Multi-device

- `readAllForExport()` exists; DHC v0.2 contract in `integrations/dhc/`.
- Cross-device identity: Android Keystore HMAC differs per device → cannot compare fingerprints directly.
- Need: `docs/architecture/MULTI-DEVICE-INGEST.md`, `CROSS-DEVICE-IDENTITY.md`, PoC merge of two synthetic exports.

## P1 — Compatibility

- API 28/33/36 tested in emulator; real devices NOT_TESTED.
- OEM guidance in `DeviceCapabilities.kt`.

## P1 — Performance

- Queue wait p95 16,245 ms (target 20 ms) — NOT MET. Need to keep long-text work off main thread.
- Main-thread snapshot cost (large CharSequence conversion) is a risk.

## P2 — Data model

- Schema v5. Need v6 only if provenance requires it. Migration additive.

## Security

- No raw messages in logs (already enforced).
- Need malformed extras / oversized input / BiDi spoofing tests.

## Test strategy

- Golden corpus for Unicode (≥ 25 cases).
- Length tests: 256 / 1024 / 4096 / 16384 / 65536.
- Retraction tests: posted→saved→removed, etc.
- Source expansion: verify package IDs.
- Multi-device: PoC merge of two synthetic exports.
- Performance: no regression.

## Next step

1. Create the long-text golden corpus (RED tests first).
2. Remove app-side truncation + separate display normalization.
3. Add content provenance.
4. Implement retraction semantics.
5. Expand source registry (verified package IDs).
6. Multi-device docs + PoC.
7. Compatibility + performance + security.
8. Update docs + commit.
