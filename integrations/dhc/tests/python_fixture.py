#!/usr/bin/env python3
"""Produce the DHC fixtures that the Android side validates.

The interoperability check has two directions:

  Android exports -> Python imports   (the host-side script)
  Python exports -> Android validates  (this file)

The second direction is the one that is easy to get wrong: a bundle made by
the Python reference tool has to be accepted by the Android side. If the two
implementations disagree about the container format, the KDF, the nonce
length or the message id, this is where it shows.

The fixtures are written to the directory given on the command line. No real
message content is involved: every body is a synthetic marker.
"""
from __future__ import annotations

import json
import os
import struct
import sys
from pathlib import Path

# The reference implementation lives next to this script.
_HERE = Path(__file__).resolve().parent
_ROOT = _HERE.parent.parent.parent
sys.path.insert(0, str(_ROOT / "integrations" / "dhc"))

from protocol import contract, crypto  # noqa: E402

PASSPHRASE = "the python side made this"


def make_bundle(messages, *, schema_version=contract.SCHEMA_VERSION,
                schema_name=contract.SCHEMA_NAME) -> contract.ExportBundle:
    """Build an export bundle from plain message dictionaries."""
    exported = [
        contract.ExportedMessage(
            message_id=contract.compute_message_id(
                conversation_id=m["conversation_id"],
                source_application=m["source_application"],
                source_timestamp=m.get("source_timestamp"),
                body=m["body"],
            ),
            conversation_id=m["conversation_id"],
            source_application=m["source_application"],
            message_kind=m.get("message_kind", "DIRECT_MESSAGE"),
            received_at=m["received_at"],
            source_timestamp=m.get("source_timestamp"),
            sender=m.get("sender", "Synthetic Sender"),
            body=m["body"],
        )
        for m in messages
    ]
    return contract.ExportBundle(
        schema_version=schema_version,
        schema_name=schema_name,
        export_id="exp_python_fixture",
        source_device_id="dev_python_fixture",
        export_created_at=1_700_000_000_000,
        messages=exported,
        cursor=None,
    )


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: python_fixture.py <output-directory>", file=sys.stderr)
        return 2
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    messages = [
        {
            "conversation_id": "conv-python-1",
            "source_application": "jp.naver.line.android",
            "message_kind": "DIRECT_MESSAGE",
            "received_at": 1_700_000_000_000,
            "source_timestamp": 1_700_000_000_000,
            "sender": "Synthetic Sender",
            "body": "synthetic python fixture message #0",
        },
        {
            "conversation_id": "conv-python-1",
            "source_application": "jp.naver.line.android",
            "message_kind": "DIRECT_MESSAGE",
            "received_at": 1_700_000_001_000,
            "source_timestamp": 1_700_000_001_000,
            "sender": "Synthetic Sender",
            "body": "synthetic python fixture message #1",
        },
    ]

    # The valid fixture.
    bundle = make_bundle(messages)
    raw = contract.serialize(bundle)
    container = crypto.encrypt_bundle(
        raw, PASSPHRASE, schema_version=bundle.schema_version
    )
    (out / "python-export.picbox").write_bytes(container)
    print(f"wrote {out / 'python-export.picbox'} ({len(container)} bytes)")

    # An unknown schema version. The container is well formed and the
    # passphrase is right, so ONLY the version can make it unacceptable.
    v99 = make_bundle(messages, schema_version=99, schema_name="PRIVATE_INBOX_EXPORT_V99")
    raw99 = contract.serialize(v99)
    container99 = crypto.encrypt_bundle(
        raw99, PASSPHRASE, schema_version=v99.schema_version
    )
    (out / "python-export-v99.picbox").write_bytes(container99)
    print(f"wrote {out / 'python-export-v99.picbox'} ({len(container99)} bytes)")

    # The plain (unencrypted) bundle, for the schema and id checks that do
    # not need the container.
    (out / "python-export.json").write_bytes(raw)
    print(f"wrote {out / 'python-export.json'} ({len(raw)} bytes)")

    # A summary the shell script can print.
    summary = {
        "messages": len(bundle.messages),
        "schema_version": bundle.schema_version,
        "schema_name": bundle.schema_name,
        "message_ids": [m.message_id for m in bundle.messages],
    }
    (out / "python-fixture-summary.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
