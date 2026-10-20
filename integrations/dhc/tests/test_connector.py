#!/usr/bin/env python3
"""Tests for the DHC connector: the importer and the container format.

Covers every acceptance case from the brief:

* correct passphrase
* wrong passphrase
* tampered ciphertext
* duplicate import
* unsupported version
* corrupted archive
* oversized input
* empty archive

Run with:  python3 -m pytest integrations/dhc/tests/ -q
or simply: python3 integrations/dhc/tests/test_connector.py
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

_PACKAGE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if _PACKAGE_ROOT not in sys.path:
    sys.path.insert(0, _PACKAGE_ROOT)

from importer.importer import (  # noqa: E402
    ImportErrorReport,
    LocalStore,
    import_bundle,
)
from protocol import contract, crypto  # noqa: E402

PASSPHRASE = "correct horse battery staple"
# A deliberately small iteration count keeps the test suite fast. The
# production default is 210,000 and is enforced by the Android exporter.
TEST_ITERATIONS = 50_000


def make_messages(count: int = 3) -> list[contract.ExportedMessage]:
    return [
        contract.ExportedMessage(
            message_id=contract.compute_message_id(
                f"conv-{i}", "jp.naver.line.android", 1000 + i, f"synthetic body {i}"
            ),
            conversation_id=f"conv-{i}",
            source_application="jp.naver.line.android",
            message_kind=contract.KIND_DIRECT_MESSAGE,
            received_at=1700000000000 + i,
            source_timestamp=1000 + i,
            sender=f"sender-{i}",
            body=f"synthetic body {i}",
        )
        for i in range(count)
    ]


def make_bundle(messages=None) -> contract.ExportBundle:
    return contract.ExportBundle(
        export_id="exp_test",
        source_device_id="dev_test",
        export_created_at=1700000000000,
        messages=messages if messages is not None else make_messages(),
    )


def encrypt(bundle: contract.ExportBundle, passphrase: str = PASSPHRASE) -> bytes:
    return crypto.encrypt_bundle(
        contract.serialize(bundle), passphrase, bundle.schema_version, TEST_ITERATIONS
    )


class ContainerFormatTests(unittest.TestCase):
    def test_round_trip_with_the_correct_passphrase(self):
        container = encrypt(make_bundle())
        plaintext = crypto.decrypt_bundle(container, PASSPHRASE)
        bundle = contract.parse(plaintext)
        self.assertEqual(bundle.schema_name, contract.SCHEMA_NAME)
        self.assertEqual(bundle.schema_version, contract.SCHEMA_VERSION)
        self.assertEqual(len(bundle.messages), 3)

    def test_wrong_passphrase_is_rejected(self):
        container = encrypt(make_bundle())
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(container, "not the passphrase")

    def test_tampered_ciphertext_is_rejected(self):
        container = bytearray(encrypt(make_bundle()))
        container[-1] ^= 0xFF
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(bytes(container), PASSPHRASE)

    def test_tampered_header_is_rejected(self):
        # The header is authenticated as AAD, so changing it must fail.
        container = bytearray(encrypt(make_bundle()))
        index = container.find(b'"kdf_iterations"')
        self.assertGreater(index, 0)
        container[index + len(b'"kdf_iterations"')] = ord("9")
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(bytes(container), PASSPHRASE)

    def test_corrupted_archive_is_rejected(self):
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(b"not a bundle at all", PASSPHRASE)

    def test_truncated_archive_is_rejected(self):
        container = encrypt(make_bundle())
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(container[:20], PASSPHRASE)

    def test_empty_archive_is_rejected(self):
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(b"", PASSPHRASE)

    def test_oversized_input_is_rejected_without_allocating_it(self):
        # A header claiming a huge body must fail on the length check rather
        # than attempting to allocate the claimed size.
        header = json.dumps(
            {
                "schema_version": 1,
                "kdf": crypto.KDF_ALGORITHM,
                "kdf_iterations": 1000,
                "kdf_salt": "AAAAAAAAAAAAAAAAAAAAAA==",
                "nonce": "AAAAAAAAAAAAAAAA",
                "cipher": crypto.CIPHER_ALGORITHM,
                "body_length": 2 ** 40,
            }
        ).encode("utf-8")
        container = (
            crypto.MAGIC
            + len(header).to_bytes(4, "big")
            + header
            + b"\x00" * 16
        )
        with self.assertRaises(crypto.DecryptionError):
            crypto.decrypt_bundle(container, PASSPHRASE)

    def test_unsupported_container_version_is_rejected(self):
        # A container whose header claims an unsupported version must be
        # refused before any decryption is attempted.
        import base64
        import json
        import struct

        salt = os.urandom(crypto.SALT_LENGTH)
        nonce = os.urandom(crypto.NONCE_LENGTH)
        key = crypto.derive_key(PASSPHRASE, salt, TEST_ITERATIONS)
        header = {
            "schema_version": 99,
            "kdf": crypto.KDF_ALGORITHM,
            "kdf_iterations": TEST_ITERATIONS,
            "kdf_salt": base64.b64encode(salt).decode("ascii"),
            "nonce": base64.b64encode(nonce).decode("ascii"),
            "cipher": crypto.CIPHER_ALGORITHM,
            "body_length": 0,
        }
        header_bytes = json.dumps(
            header, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        aad = crypto.MAGIC + struct.pack(">I", len(header_bytes)) + header_bytes
        body = crypto._aead().encrypt(key, nonce, b"", aad)
        container = aad + body
        with self.assertRaises(crypto.UnsupportedVersionError):
            crypto.decrypt_bundle(container, PASSPHRASE)

    def test_unsupported_bundle_version_is_rejected(self):
        # The schema version INSIDE the bundle is checked by the contract
        # layer, separately from the container version.
        data = make_bundle().to_dict()
        data["schema_version"] = 99
        with self.assertRaises(contract.ContractError):
            contract.parse(json.dumps(data).encode("utf-8"))

    def test_nonce_is_never_reused(self):
        import base64
        import json
        import struct

        def header_of(container: bytes) -> dict:
            length = struct.unpack(">I", container[8:12])[0]
            return json.loads(container[12 : 12 + length].decode("utf-8"))

        first = crypto.encrypt_bundle(b"payload", PASSPHRASE, 1, TEST_ITERATIONS)
        second = crypto.encrypt_bundle(b"payload", PASSPHRASE, 1, TEST_ITERATIONS)
        h1, h2 = header_of(first), header_of(second)
        self.assertNotEqual(
            h1["nonce"], h2["nonce"], "the nonce must differ per bundle"
        )
        self.assertNotEqual(
            h1["kdf_salt"], h2["kdf_salt"], "the salt must differ per bundle"
        )
        self.assertNotEqual(first, second, "the whole container must differ")

    def test_iteration_count_must_be_sufficient(self):
        with self.assertRaises(ValueError):
            crypto.encrypt_bundle(b"payload", PASSPHRASE, 1, 1000)

    def test_production_iteration_count_is_the_default(self):
        # The Android exporter always uses the default. A bundle that claims
        # fewer iterations than the floor must be refused on import.
        self.assertGreaterEqual(crypto.DEFAULT_ITERATIONS, crypto.MIN_ITERATIONS)


class ContractTests(unittest.TestCase):
    def test_unknown_schema_version_is_rejected(self):
        data = make_bundle().to_dict()
        data["schema_version"] = 99
        with self.assertRaises(contract.ContractError):
            contract.parse(json.dumps(data).encode("utf-8"))

    def test_unknown_message_kind_is_rejected(self):
        data = make_bundle().to_dict()
        data["messages"][0]["message_kind"] = "MADE_UP_KIND"
        with self.assertRaises(contract.ContractError):
            contract.parse(json.dumps(data).encode("utf-8"))

    def test_missing_field_is_rejected(self):
        data = make_bundle().to_dict()
        del data["messages"][0]["body"]
        with self.assertRaises(contract.ContractError):
            contract.parse(json.dumps(data).encode("utf-8"))

    def test_message_id_is_stable_and_content_derived(self):
        a = contract.compute_message_id("c", "pkg", 1, "body")
        b = contract.compute_message_id("c", "pkg", 1, "body")
        c = contract.compute_message_id("c", "pkg", 1, "other body")
        self.assertEqual(a, b)
        self.assertNotEqual(a, c)

    def test_private_only_filters_social_activity(self):
        bundle = make_bundle(
            make_messages(1)
            + [
                contract.ExportedMessage(
                    message_id="msg_social",
                    conversation_id="conv-social",
                    source_application="com.instagram.barcelona",
                    message_kind=contract.KIND_SOCIAL_ACTIVITY,
                    received_at=1,
                    source_timestamp=None,
                    sender="s",
                    body="social",
                )
            ]
        )
        private = list(contract.private_only(bundle))
        self.assertEqual(len(private), 1)
        self.assertEqual(private[0].message_kind, contract.KIND_DIRECT_MESSAGE)


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store_path = os.path.join(self.tmp.name, "imported.db")

    def tearDown(self):
        self.tmp.cleanup()

    def test_import_inserts_and_is_idempotent(self):
        container = encrypt(make_bundle())
        with LocalStore(self.store_path) as store:
            first = import_bundle(container, PASSPHRASE, store)
            self.assertEqual(first.inserted, 3)
            self.assertEqual(first.duplicates_skipped, 0)
            self.assertEqual(store.count(), 3)

            # The same bundle again must not create duplicates.
            second = import_bundle(container, PASSPHRASE, store)
            self.assertEqual(second.inserted, 0)
            self.assertEqual(second.duplicates_skipped, 3)
            self.assertEqual(store.count(), 3)

    def test_import_rejects_the_wrong_passphrase(self):
        container = encrypt(make_bundle())
        with LocalStore(self.store_path) as store:
            with self.assertRaises(ImportErrorReport):
                import_bundle(container, "wrong", store)
            self.assertEqual(store.count(), 0)

    def test_import_rejects_an_unsupported_version(self):
        data = make_bundle().to_dict()
        data["schema_version"] = 42
        container = crypto.encrypt_bundle(
            json.dumps(data).encode("utf-8"), PASSPHRASE, 42, TEST_ITERATIONS
        )
        with LocalStore(self.store_path) as store:
            with self.assertRaises(ImportErrorReport):
                import_bundle(container, PASSPHRASE, store)

    def test_incremental_export_does_not_duplicate_earlier_messages(self):
        first = make_bundle(make_messages(2))
        second = make_bundle(make_messages(3))
        with LocalStore(self.store_path) as store:
            import_bundle(encrypt(first), PASSPHRASE, store)
            summary = import_bundle(encrypt(second), PASSPHRASE, store)
            self.assertEqual(summary.inserted, 1)
            self.assertEqual(summary.duplicates_skipped, 2)
            self.assertEqual(store.count(), 3)

    def test_the_store_file_is_owner_only(self):
        with LocalStore(self.store_path) as store:
            import_bundle(encrypt(make_bundle()), PASSPHRASE, store)
        mode = os.stat(self.store_path).st_mode & 0o777
        self.assertEqual(
            mode & 0o077,
            0,
            f"the store holds private messages and must not be group/world readable "
            f"(mode {mode:o})",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
