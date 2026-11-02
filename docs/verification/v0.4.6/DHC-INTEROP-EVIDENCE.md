# v0.4.6 — DHC Kotlin↔Python Interoperability Evidence (T09)

## Environment
- Python: 3.14.5 (`/Library/Frameworks/Python.framework/Versions/3.14/bin/python3`)
- Emulator: emulator-5554, API 36
- Kotlin exporter: `dhc/DhcExporter.kt` (V1 formula, UNMODIFIED — verified `git diff HEAD` empty on the file)

## Commands & results

| Step | Command | Exit | Result |
|---|---|---|---|
| Python reference tests | `python3 -m pytest integrations/dhc/tests/test_connector.py integrations/dhc/tests/test_multi_device_merge.py -q` | 0 | **27 passed** |
| Kotlin export + Python-fixture tests | `adb shell am instrument -e class DhcExportInstrumentedTest,DhcPythonFixtureInstrumentedTest` | 0 | **14 passed** (7+7) |
| REAL end-to-end | device export → `adb pull` → Python importer via `integrations/dhc/tests/host_interop.sh` | 0 | **`RESULT: PASS - Android export -> Python import works end to end.`** |

## The real round trip
1. `DhcPythonFixtureInstrumentedTest` exported a bundle on-device
   (`/sdcard/Download/dhc-export-test.picbox`) using the production exporter.
2. `host_interop.sh` pulled it and ran the Python reference importer.
3. Report: `messages_in_bundle=2`, `schema_version=1`, `ok=true`.
4. **Wrong passphrase rejected** (`dhc-export-wrongpass.picbox`) — the GCM auth
   tag refused it. Evidence line: `OK: the wrong passphrase is rejected.`

## Contract checks
| Check | Status |
|---|---|
| Kotlin bundle readable by the original Python importer | PASS (real round trip) |
| Message ID matches the historical formula | PASS (V1 `computeMessageId` untouched; golden vectors in Kotlin+Python) |
| Legacy conversation key unchanged | PASS (no rewrite path exists) |
| Canonical ID does NOT replace V1 identity | PASS (canonical id never enters `computeMessageId` — pinned by `theCanonicalIdMustNeverEnterTheFingerprintTitleSlot`) |
| Repeat import does not duplicate | PASS (Python `message_id PRIMARY KEY` tests in the 27) |
| Wrong passphrase rejected | PASS (real run) |
| Tampered ciphertext | PASS (GCM tag; covered by the Python suite) |
| Unicode/CJK/Emoji | PASS (golden vectors + bundle with CJK content) |
| Canonical-ID-specific V1 fields | NOT_SUPPORTED in V1 (by design — additive versioning only) |

## Bundle artifact
- `app/build/outputs/dhc-export-test.picbox` (exported by the device)
- Interop report: `app/build/outputs/dhc-interop-report.json`

## Gaps
- Canonical conversation id is NOT yet exported in any form (V1 has no field for
  it). Any future export of it must be a NEW versioned contract, per
  `docs/handoff/v046/05-DHC-COMPATIBILITY.md`.
