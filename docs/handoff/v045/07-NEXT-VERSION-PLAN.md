# v0.4.5 Next-Version Plan

Every item below is **DESIGN_ONLY** unless a code path and a passing test are
cited. Nothing here is implemented in v0.4.5.

---

## 1. Persistent Intake Journal — DESIGN_ONLY

**Problem.** Loss window: process death between `capture()` and the SQLite
commit loses the batch (see 06 contract §5). The platform will not re-deliver.

**Design.** Before the queue handoff, append a compact record to a local
append-only file (package, source ts, conversation key, discriminator,
sealed content, trace id) — the same sealed bytes that would go to SQLite, so
no new plaintext at rest. On startup, replay uncommitted records through
`flushPrepared()`; on commit, mark the record consumed and truncate
periodically. Bounded by size (e.g. 4 MB) with oldest-first eviction and a
`JOURNAL_EVICTED` diagnostic.

**Dependencies.** `CryptoStore` (already available); file IO on the worker
thread only; the existing dedup key makes replay idempotent.

**Risks.** Double-write cost (a second fsync per batch) → must be measured
against the burst benchmark before enabling; journal corruption on power loss →
needs a checksum per record and a "skip to next valid record" reader.

**Acceptance before implementation.** A fault-injection test that kills the
process between prepare and commit, restarts, and asserts the row appears
exactly once; plus a burst benchmark showing p95 latency regression < 20 %.

---

## 2. Long-term retention and archival — DESIGN_ONLY

**Problem.** Rows grow without bound; the user has no archiving story beyond
`retentionPrunesOldMessages`.

**Design.** Tiered retention (hot in SQLite, cold in an encrypted archive file),
user-selectable horizon, and an explicit "prune" preview that reports how many
rows would be deleted **before** deleting them.

**Risks.** Destructive by nature — requires the same guard pattern as
`TestDataIsolation`; a bug here deletes real history.

**Acceptance.** A test that proves no row is removed without an explicit
opt-in call, and that a prune preview matches the actual deletion count.

---

## 3. DHC offline aggregation — DESIGN_ONLY

**Problem.** Multi-device merge exists as a PoC contract only.

**Design.** Extend the existing encrypted export with a merge manifest (device
id, per-device high-water mark, fingerprint set digest), so two exports can be
merged without decrypting twice, and conflicts resolve by fingerprint.

**Risks.** Fingerprint sets for large archives are big → needs Bloom-filter +
verified set exchange; merge must never resurrect a message the user deleted.

**Acceptance.** A merge test with overlapping and disjoint devices asserting
`rows_after == rows_a + rows_b - overlap`.

---

## 4. Multi-device import contract — DESIGN_ONLY

Formalise the v1 export schema (version, required fields, ordering, checksum),
with a compatibility test that rejects an unknown major version and accepts a
newer minor one. Depends on §3.

---

## 5. Image URI capture research — DESIGN_ONLY

**Problem.** Media URIs are read inside the callback because the grant is
revoked afterwards; failures are recorded as `MEDIA_URI_UNAVAILABLE` but the
reliability of that read across OEMs is unmeasured.

**Design.** Research task: measure read success rate per OEM/API level, and
evaluate whether a `ContentResolver` persistable grant is obtainable for the
relevant providers. No new permission may be added.

**Acceptance.** A measured success-rate table, not an implementation.

---

## 6. Android cross-brand compatibility matrix — DESIGN_ONLY

Matrix of listener survival / rebind latency / notification access UX across
major OEM ROMs. Feeds §7 (mirror) decisions and the reliability contract's OEM
section. Requires real devices; cannot be closed on the emulator.

---

## 7. Optional notification mirror — DESIGN_ONLY

**Problem.** Users may want a local mirror of captured messages as a
notification. This is a privacy-sensitive opt-in.

**Design.** Off by default; explicit opt-in per source; the mirror shows only
what the archive already holds; `FLAG_SECURE` applies; a permanent opt-in marker
in settings so it cannot be enabled silently.

**Risks.** Leaks captured content onto the lock screen — must default to
`VISIBILITY_SECRET` and refuse to mirror when the device policy forbids it.

**Acceptance.** A test asserting the mirror cannot be enabled without an explicit
user action, and that it is off after a fresh install.

---

## 8. Rust / UniFFI core PoC — DESIGN_ONLY

**Problem.** Crypto and parsing are Kotlin; a shared core would help future
platforms.

**Design.** PoC only: wrap the existing AES-GCM + fingerprint algorithm in Rust
behind UniFFI and prove byte-identical output against the Kotlin vectors. No
production integration in this PoC.

**Risks.** Toolchain bloat, NDK build time, key handling must stay in the
Keystore (Rust may not hold key material).

**Acceptance.** Golden-vector parity test between Kotlin and Rust, plus an APK
size delta measurement. Do not ship until the delta is justified.

---

## 9. Dark mode — DESIGN_ONLY

Follows the platform theme; must not break `FLAG_SECURE` or the Telegram-Classic
layout rules (native views only). Acceptance: screenshot comparison at both
themes and no new dependency.

---

## Ordering recommendation

1 → 2 → 3 are the reliability/durability spine and should be sequenced;
4 depends on 3; 5, 6, 7 are research/UX; 8 and 9 are independent.
None of them may begin before the v0.4.5 reliability candidate is committed and
independently reviewed.
