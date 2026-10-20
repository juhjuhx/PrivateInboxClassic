"""Local import adapter for Private Inbox bundles.

This is a standalone library and CLI. It does not require, and does not modify,
the DHC repository: the brief is explicit that DHC is AGPL-3.0 and that no AGPL
source may be copied into the MIT-licensed Private Inbox core. This adapter
implements only the *data contract* defined in ``protocol/contract.py`` and
the *container format* defined in ``protocol/crypto.py``.

What it does:

* Reads an encrypted bundle from a file.
* Verifies the passphrase and decrypts it.
* Validates the schema version. An unknown version is rejected.
* Imports messages into a local store, skipping ones that are already there.
* Produces a summary and an error report.

What it deliberately does NOT do:

* It never prints message bodies to the terminal. The summary contains counts
  and identifiers only.
* It never sends anything anywhere. There is no network code in this module.
"""

from __future__ import annotations

import json
import os
import sqlite3
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import sys as _sys
import os as _os

# The importer is usable both as a package module and as a standalone script.
_PACKAGE_ROOT = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))
if _PACKAGE_ROOT not in _sys.path:
    _sys.path.insert(0, _PACKAGE_ROOT)

from protocol import contract, crypto  # noqa: E402


@dataclass
class ImportSummary:
    """What an import did. Contains no message content."""

    export_id: str
    source_device_id: str
    schema_version: int
    messages_in_bundle: int
    inserted: int
    duplicates_skipped: int
    private_messages: int
    social_activity: int
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "export_id": self.export_id,
            "source_device_id": self.source_device_id,
            "schema_version": self.schema_version,
            "messages_in_bundle": self.messages_in_bundle,
            "inserted": self.inserted,
            "duplicates_skipped": self.duplicates_skipped,
            "private_messages": self.private_messages,
            "social_activity": self.social_activity,
            "errors": self.errors,
        }


class ImportErrorReport(Exception):
    """Raised when a bundle cannot be imported at all."""


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS imported_messages (
    message_id      TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    source_app      TEXT NOT NULL,
    message_kind    TEXT NOT NULL,
    received_at     INTEGER NOT NULL,
    source_ts       INTEGER,
    sender          TEXT NOT NULL,
    body            TEXT NOT NULL,
    attachment_meta TEXT,
    first_imported_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS imported_conversation
    ON imported_messages(conversation_id);
CREATE INDEX IF NOT EXISTS imported_received
    ON imported_messages(received_at DESC);
"""


class LocalStore:
    """A local SQLite store for imported messages."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.path))
        self.conn.executescript(SCHEMA_SQL)
        self.conn.commit()
        # The store holds private messages, so the file must not be readable by
        # other users on the machine. sqlite creates the file lazily with the
        # process umask, so the mode is fixed AFTER the schema is written.
        self._restrict_permissions()

    def _restrict_permissions(self) -> None:
        for target in (
            self.path,
            Path(str(self.path) + "-wal"),
            Path(str(self.path) + "-shm"),
        ):
            if not target.exists():
                continue
            try:
                os.chmod(target, 0o600)
            except OSError:
                # Best effort: a filesystem that does not support POSIX modes
                # must not stop the import from working.
                pass

    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> "LocalStore":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def contains(self, message_id: str) -> bool:
        row = self.conn.execute(
            "SELECT 1 FROM imported_messages WHERE message_id = ?", (message_id,)
        ).fetchone()
        return row is not None

    def insert(self, message: contract.ExportedMessage, now: int) -> bool:
        """Insert a message. Returns False when it was already present."""
        if self.contains(message.message_id):
            return False
        attachment = (
            json.dumps(message.attachment_metadata.to_dict(), sort_keys=True)
            if message.attachment_metadata
            else None
        )
        self.conn.execute(
            "INSERT INTO imported_messages (message_id, conversation_id, source_app,"
            " message_kind, received_at, source_ts, sender, body, attachment_meta,"
            " first_imported_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (
                message.message_id,
                message.conversation_id,
                message.source_application,
                message.message_kind,
                message.received_at,
                message.source_timestamp,
                message.sender,
                message.body,
                attachment,
                now,
            ),
        )
        return True

    def count(self) -> int:
        return self.conn.execute("SELECT COUNT(*) FROM imported_messages").fetchone()[0]


def import_bundle(
    container: bytes,
    passphrase: str,
    store: LocalStore,
    now: Optional[int] = None,
) -> ImportSummary:
    """Decrypt, validate and import a bundle. Idempotent."""
    now = int(time.time() * 1000) if now is None else now
    try:
        plaintext = crypto.decrypt_bundle(container, passphrase)
    except crypto.UnsupportedVersionError as exc:
        raise ImportErrorReport(f"unsupported bundle: {exc}") from exc
    except crypto.DecryptionError as exc:
        raise ImportErrorReport(str(exc)) from exc

    try:
        bundle = contract.parse(plaintext)
    except contract.ContractError as exc:
        raise ImportErrorReport(f"invalid bundle: {exc}") from exc

    summary = ImportSummary(
        export_id=bundle.export_id,
        source_device_id=bundle.source_device_id,
        schema_version=bundle.schema_version,
        messages_in_bundle=len(bundle.messages),
        inserted=0,
        duplicates_skipped=0,
        private_messages=0,
        social_activity=0,
    )

    for message in bundle.messages:
        if message.message_kind in contract.PRIVATE_MESSAGE_KINDS:
            summary.private_messages += 1
        elif message.message_kind == contract.KIND_SOCIAL_ACTIVITY:
            summary.social_activity += 1
        try:
            if store.insert(message, now):
                summary.inserted += 1
            else:
                summary.duplicates_skipped += 1
        except sqlite3.Error as exc:
            # One bad row must not abort the whole import.
            summary.errors.append(f"row {message.message_id}: {type(exc).__name__}")

    store.conn.commit()
    return summary


def import_file(
    path: Path, passphrase: str, store: LocalStore
) -> ImportSummary:
    try:
        container = Path(path).read_bytes()
    except OSError as exc:
        raise ImportErrorReport(f"could not read {path}: {exc}") from exc
    return import_bundle(container, passphrase, store)


def main(argv: Optional[list[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(
        prog="dhc-importer",
        description="Import a Private Inbox export bundle into a local store.",
    )
    parser.add_argument("bundle", type=Path, help="path to the encrypted bundle")
    parser.add_argument(
        "--store",
        type=Path,
        default=Path.home() / ".private-inbox" / "imported.db",
        help="path to the local database (default: %(default)s)",
    )
    parser.add_argument(
        "--passphrase-file",
        type=Path,
        help="read the passphrase from this file instead of prompting",
    )
    args = parser.parse_args(argv)

    if args.passphrase_file:
        passphrase = args.passphrase_file.read_text(encoding="utf-8").strip("\n")
    else:
        import getpass

        passphrase = getpass.getpass("bundle passphrase: ")

    try:
        with LocalStore(args.store) as store:
            summary = import_file(args.bundle, passphrase, store)
    except ImportErrorReport as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, indent=2))
        return 1

    # Only counts and identifiers are printed. No message content.
    print(json.dumps({"ok": True, **summary.to_dict()}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
