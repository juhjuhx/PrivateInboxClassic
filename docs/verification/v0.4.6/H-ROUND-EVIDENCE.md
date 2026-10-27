# v0.4.6 RC Hardening Round (H01–H05) Evidence

## H01 — False-positive test repairs (PASS)
| Fix | Before | After |
|---|---|---|
| View hierarchy assertion | "any avatar marker matched" (Set, could pass with wrong rows) | MainActivity tags every inbox row with its conversation key; the test asserts EXACT row count, exact per-key occurrences (duplicates cannot collapse), and exact page-1 key ORDER against the SQL summary |
| Date range | asserted nothing about dates | drives the real date predicate: ≥newTs → 2 rows, ≥0 → 5, past-all → 0 |
| Real UI search | only called filteredEntries() | drives showSearch() → real EditText input → TextWatcher → REAL rendered results in the hierarchy |
| 100-conversation pagination | one arbitrary marker | page-1 must equal SQL summary's first 50 keys in order |

`Qa01ReadModelAndUiTest`: **8/8 PASS**

## H02 — Full-suite flake convergence (PARTIAL: 4 causes fixed, 1 unresolved)
### Root causes found (first-hand stack/message evidence)
1. `testJ` counted the whole table → leftovers oscillated 3↔5 → scoped to this test's rows. 
2. Self-test notification stayed in the tray → catch-up re-captured it after clear as a NEW row → setUp now cancels the tray BEFORE clear + per-run unique markers.
3. `SQLITE_BUSY` on the OPEN path (`SQLiteDatabase.getVersion`) under a long burst transaction → `PRAGMA busy_timeout=5000` in `onConfigure`. Pair runs: 3/3 PASS (was 1/3).
4. `bodyOf()` decrypted with the instrumentation package → uses the row's own package_name now.

### FLAKY_UNRESOLVED
`SystemNotificationE2E` fails intermittently ONLY in the full 239-test run
(marker commits, link never appears within 40 s). Not reproducible by any
neighbor suite, pair, or diagnostics capture (LINK_FAILED never logged). Two
diagnosis attempts made per the contract; recorded, not guessed.

### Final fresh numbers
| Run | Result |
|---|---|
| Full suite (239) | 1 flaky (SystemE2E link), 0 product failures |
| Qa01ReadModelAndUiTest | 8/8 |
| SystemNotificationE2E alone | 3/3 |
| BurstCapture alone | 10/10 |
| Pair BurstCapture+SystemE2E | 3/3 PASS post-busy_timeout |

## H04 — Diff audit (PASS)
- Private paths (`/Users/`, real names): 0 occurrences in tracked.diff
- keystore/password mentions: 7, all inside docs/SIGNING.md (fingerprint
  documentation + the public `storepass android` debug convention) — safe
- Snapshot: `~/Desktop/pi-v046-rc-snapshot/` (208 KB, mode 700, SHA-256 manifest + RESTORE.md)

## H05 — Final matrix
See FINAL-TEST-MATRIX.md (regenerated). Core product suites all green in
isolation; the only red is the recorded flake.
