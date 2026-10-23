#!/usr/bin/env python3
"""Multi-device merge PoC (v0.4.5).

Two synthetic devices export their own encrypted DHC containers. A third store
imports BOTH, in both orders, and the test asserts the merge contract
documented in docs/architecture/MULTI-DEVICE-INGEST.md:

* identical message_id on both devices -> exactly one row (idempotent union)
* same conversation+timestamp, different body -> two rows (content-derived ids)
* import order does not change the resulting set
* re-importing a bundle adds nothing

The bundles go through the REAL path: contract.serialize() ->
crypto.encrypt_bundle() with a per-device passphrase -> importer.import_bundle(),
so the PoC exercises the same decryption a second device would perform.

Run with:  python3 -m pytest integrations/dhc/tests/test_multi_device_merge.py -q
or simply: python3 integrations/dhc/tests/test_multi_device_merge.py
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

_PACKAGE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if _PACKAGE_ROOT not in sys.path:
    sys.path.insert(0, _PACKAGE_ROOT)

from importer.importer import LocalStore, import_bundle  # noqa: E402
from protocol import contract, crypto  # noqa: E402

# Deliberately small to keep the suite fast; production enforces 210,000.
TEST_ITERATIONS = 50_000


def msg(device_tag: str, i: int, body: str | None = None) -> contract.ExportedMessage:
    """A message whose id is derived ONLY from content (not from the device)."""
    conversation = f"conv-{device_tag}-{i}"
    ts = 1000 + i
    b = body if body is not None else f"body {device_tag} {i}"
    return contract.ExportedMessage(
        message_id=contract.compute_message_id(
            conversation, "jp.naver.line.android", ts, b
        ),
        conversation_id=conversation,
        source_application="jp.naver.line.android",
        message_kind=contract.KIND_DIRECT_MESSAGE,
        received_at=1_700_000_000_000 + i,
        source_timestamp=ts,
        sender=f"sender-{i}",
        body=b,
    )


def bundle_for(device_id: str, messages: list) -> contract.ExportBundle:
    return contract.ExportBundle(
        export_id=f"export-{device_id}",
        source_device_id=device_id,
        export_created_at=1_700_000_000_000,
        messages=messages,
    )


def encrypt(bundle: contract.ExportBundle, passphrase: str) -> bytes:
    return crypto.encrypt_bundle(
        contract.serialize(bundle), passphrase, bundle.schema_version, TEST_ITERATIONS
    )


class MultiDeviceMergeTest(unittest.TestCase):
    def _import(self, store: LocalStore, bundle: contract.ExportBundle):
        # Each device encrypts with its own passphrase; the merging store
        # must be given the matching one, exactly as a human operator would.
        passphrase = f"passphrase-for-{bundle.source_device_id}"
        return import_bundle(encrypt(bundle, passphrase), passphrase, store)

    def test_identical_message_on_both_devices_collapses_to_one_row(self):
        # Device A and device B both captured the same user-visible message:
        # same conversation, same source timestamp, same body -> same id.
        shared = contract.ExportedMessage(
            message_id=contract.compute_message_id(
                "conv-shared-1", "jp.naver.line.android", 1001, "shared body"
            ),
            conversation_id="conv-shared-1",
            source_application="jp.naver.line.android",
            message_kind=contract.KIND_DIRECT_MESSAGE,
            received_at=1_700_000_000_001,
            source_timestamp=1001,
            sender="shared-sender",
            body="shared body",
        )
        b_a = bundle_for("device-a", [shared, msg("A", 2)])
        b_b = bundle_for("device-b", [shared, msg("B", 7)])
        with tempfile.TemporaryDirectory() as td:
            store = LocalStore(Path(td) / "merge.db")
            s1 = self._import(store, b_a)
            s2 = self._import(store, b_b)
            self.assertEqual(0, s1.duplicates_skipped)
            # The shared message is skipped the second time.
            self.assertEqual(1, s2.duplicates_skipped)
            self.assertEqual(1, s2.inserted)
            rows = store.conn.execute(
                "SELECT COUNT(*) FROM imported_messages"
            ).fetchone()[0]
            self.assertEqual(3, rows)

    def test_same_conversation_same_second_different_body_keeps_both(self):
        # A retracted-and-resent message: same conversation, same second,
        # different content. Content-derived ids keep BOTH records.
        m1 = msg("A", 1, body="first version")
        m2 = msg("A", 1, body="second version")
        self.assertNotEqual(m1.message_id, m2.message_id)
        with tempfile.TemporaryDirectory() as td:
            store = LocalStore(Path(td) / "merge.db")
            self._import(store, bundle_for("device-a", [m1]))
            self._import(store, bundle_for("device-a", [m2]))
            rows = store.conn.execute(
                "SELECT COUNT(*) FROM imported_messages"
            ).fetchone()[0]
            self.assertEqual(2, rows)

    def test_import_order_does_not_change_the_resulting_set(self):
        b_a = bundle_for("device-a", [msg("A", 1), msg("A", 2)])
        b_b = bundle_for("device-b", [msg("B", 1), msg("A", 1), msg("B", 2)])
        with tempfile.TemporaryDirectory() as td1:
            s1 = LocalStore(Path(td1) / "a.db")
            self._import(s1, b_a)
            self._import(s1, b_b)
        with tempfile.TemporaryDirectory() as td2:
            s2 = LocalStore(Path(td2) / "b.db")
            self._import(s2, b_b)
            self._import(s2, b_a)

        def dump(path: str) -> list:
            store = LocalStore(Path(path))
            return sorted(
                store.conn.execute(
                    "SELECT message_id, conversation_id, sender FROM imported_messages"
                ).fetchall()
            )

        self.assertEqual(dump(str(Path(td1) / "a.db")), dump(str(Path(td2) / "b.db")))

    def test_reimporting_both_bundles_adds_nothing(self):
        b_a = bundle_for("device-a", [msg("A", 1)])
        b_b = bundle_for("device-b", [msg("B", 1)])
        with tempfile.TemporaryDirectory() as td:
            store = LocalStore(Path(td) / "merge.db")
            self._import(store, b_a)
            self._import(store, b_b)
            s3 = self._import(store, b_a)
            s4 = self._import(store, b_b)
            self.assertEqual(1, s3.duplicates_skipped)
            self.assertEqual(1, s4.duplicates_skipped)
            rows = store.conn.execute(
                "SELECT COUNT(*) FROM imported_messages"
            ).fetchone()[0]
            self.assertEqual(2, rows)


if __name__ == "__main__":
    unittest.main()
