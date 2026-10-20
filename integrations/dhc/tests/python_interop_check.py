#!/usr/bin/env python3
"""Cross-platform interoperability check for the DHC connector.

Reads a bundle produced by the ANDROID exporter, decrypts it with the PYTHON
reference implementation, and writes a small JSON report. The Android
instrumentation test calls this script and asserts on the report, which is what
proves the two sides agree on:

* the PBKDF2-HMAC-SHA256 derivation,
* the AES-256-GCM container layout,
* the header encoding,
* the JSON message shape.

Usage:
    python_interop_check.py <bundle> <passphrase> <report.json>

Exit code 0 on success, 1 on any failure.
"""

from __future__ import annotations

import json
import os
import sys

_PACKAGE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if _PACKAGE_ROOT not in sys.path:
    sys.path.insert(0, _PACKAGE_ROOT)

from protocol import contract, crypto  # noqa: E402


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: python_interop_check.py <bundle> <passphrase> <report>")
        return 2
    bundle_path, passphrase, report_path = sys.argv[1], sys.argv[2], sys.argv[3]

    report: dict = {"ok": False}
    try:
        with open(bundle_path, "rb") as handle:
            container = handle.read()

        report["header"] = crypto.describe(container)

        plaintext = crypto.decrypt_bundle(container, passphrase)
        bundle = contract.parse(plaintext)

        report.update(
            {
                "ok": True,
                "schema_version": bundle.schema_version,
                "export_id": bundle.export_id,
                "source_device_id": bundle.source_device_id,
                "messages_in_bundle": len(bundle.messages),
                "private_messages": sum(
                    1
                    for m in bundle.messages
                    if m.message_kind in contract.PRIVATE_MESSAGE_KINDS
                ),
                "social_activity": sum(
                    1
                    for m in bundle.messages
                    if m.message_kind == contract.KIND_SOCIAL_ACTIVITY
                ),
                "message_ids": [m.message_id for m in bundle.messages],
                "conversation_ids": sorted({m.conversation_id for m in bundle.messages}),
            }
        )
    except Exception as exc:  # noqa: BLE001 - the report must capture any failure
        report = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}

    with open(report_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)
    return 0 if report.get("ok") else 1


if __name__ == "__main__":
    raise SystemExit(main())
