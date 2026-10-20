"""PRIVATE_INBOX_EXPORT_V1 — the wire format for a Private Inbox export.

This module is the single source of truth for the export contract. Both the
Android exporter and the DHC importer validate against it.

Design rules that this format obeys:

* **Versioned.** ``SCHEMA_VERSION`` is checked on import. An unknown version is
  REJECTED, never guessed at.
* **Idempotent.** ``message_id`` is a stable, content-derived identifier, so
  importing the same bundle twice must not create duplicates. It is NOT the
  Android SQLite row id: row ids are local to one device and would collide
  across devices or after a reinstall.
* **Self-describing.** Every field the importer needs is in the bundle.
* **Content-free metadata.** A bundle can describe a photo without carrying
  the photo bytes (photos are deferred for this round).

The Android side never exports the Keystore key. The bundle is re-encrypted
with a passphrase-derived key that the user supplies at import time.
"""

from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass, field, asdict
from typing import Any, Iterable, Optional

SCHEMA_VERSION = 1
SCHEMA_NAME = "PRIVATE_INBOX_EXPORT_V1"

# The message kinds the Android classifier produces. Kept as plain strings so
# an importer written in another language does not need this module.
KIND_DIRECT_MESSAGE = "DIRECT_MESSAGE"
KIND_GROUP_MESSAGE = "GROUP_MESSAGE"
KIND_MENTION = "MENTION"
KIND_REPLY = "REPLY"
KIND_SOCIAL_ACTIVITY = "SOCIAL_ACTIVITY"
KIND_UNKNOWN = "UNKNOWN"

MESSAGE_KINDS = frozenset(
    {
        KIND_DIRECT_MESSAGE,
        KIND_GROUP_MESSAGE,
        KIND_MENTION,
        KIND_REPLY,
        KIND_SOCIAL_ACTIVITY,
        KIND_UNKNOWN,
    }
)

# Only message-like kinds are private conversations. Social activity is
# deliberately excluded from the default private inbox on both sides.
PRIVATE_MESSAGE_KINDS = frozenset(
    {KIND_DIRECT_MESSAGE, KIND_GROUP_MESSAGE, KIND_MENTION, KIND_REPLY}
)


class ContractError(ValueError):
    """Raised when a bundle violates the contract."""


@dataclass
class Attachment:
    """Metadata for a captured photo. The bytes are not included yet."""

    kind: str = "image"
    mime_type: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None
    byte_length: Optional[int] = None
    # A stable identifier for the same photo across exports.
    media_id: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return {k: v for k, v in asdict(self).items() if v is not None}


@dataclass
class ExportedMessage:
    """One captured notification, in the portable form.

    ``message_id`` is derived from the conversation, the source timestamp and
    the body, so the same message always produces the same id on every device
    and after every reinstall. That is what makes the import idempotent.
    """

    message_id: str
    conversation_id: str
    source_application: str
    message_kind: str
    received_at: int
    source_timestamp: Optional[int]
    sender: str
    body: str
    attachment_metadata: Optional[Attachment] = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "message_id": self.message_id,
            "conversation_id": self.conversation_id,
            "source_application": self.source_application,
            "message_kind": self.message_kind,
            "received_at": self.received_at,
            "source_timestamp": self.source_timestamp,
            "sender": self.sender,
            "body": self.body,
        }
        if self.attachment_metadata is not None:
            out["attachment_metadata"] = self.attachment_metadata.to_dict()
        return out

    @staticmethod
    def from_dict(data: dict[str, Any]) -> "ExportedMessage":
        for required in (
            "message_id",
            "conversation_id",
            "source_application",
            "message_kind",
            "received_at",
            "sender",
            "body",
        ):
            if required not in data:
                raise ContractError(f"message is missing '{required}'")
        kind = data["message_kind"]
        if kind not in MESSAGE_KINDS:
            raise ContractError(f"unknown message_kind '{kind}'")
        if not isinstance(data["received_at"], int):
            raise ContractError("received_at must be an integer epoch value")
        attachment = data.get("attachment_metadata")
        return ExportedMessage(
            message_id=str(data["message_id"]),
            conversation_id=str(data["conversation_id"]),
            source_application=str(data["source_application"]),
            message_kind=kind,
            received_at=int(data["received_at"]),
            source_timestamp=(
                int(data["source_timestamp"])
                if data.get("source_timestamp") is not None
                else None
            ),
            sender=str(data["sender"]),
            body=str(data["body"]),
            attachment_metadata=Attachment(**attachment) if attachment else None,
        )


def compute_message_id(
    conversation_id: str,
    source_application: str,
    source_timestamp: Optional[int],
    body: str,
) -> str:
    """Derive a stable, portable message id.

    Deliberately NOT a database row id: row ids are device-local and would
    collide across devices, and would change if the database were rebuilt.

    The digest is over the content that identifies the message. The body is
    included so that two different messages in the same conversation at the
    same second do not collide.
    """
    material = "\x00".join(
        [
            conversation_id,
            source_application,
            "" if source_timestamp is None else str(source_timestamp),
            body,
        ]
    )
    digest = hashlib.sha256(material.encode("utf-8")).digest()
    return "msg_" + base64.urlsafe_b64encode(digest[:15]).decode("ascii").rstrip("=")


@dataclass
class ExportBundle:
    """The complete export payload."""

    export_id: str
    source_device_id: str
    export_created_at: int
    messages: list[ExportedMessage] = field(default_factory=list)
    schema_version: int = SCHEMA_VERSION
    schema_name: str = SCHEMA_NAME
    # Set by the exporter when it produced an incremental slice.
    cursor: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "schema_name": self.schema_name,
            "export_id": self.export_id,
            "source_device_id": self.source_device_id,
            "export_created_at": self.export_created_at,
            "cursor": self.cursor,
            "messages": [m.to_dict() for m in self.messages],
        }

    @staticmethod
    def from_dict(data: dict[str, Any]) -> "ExportBundle":
        version = data.get("schema_version")
        if version != SCHEMA_VERSION:
            # Reject unknown versions outright. Guessing would risk silently
            # mis-importing data under the wrong schema.
            raise ContractError(
                f"unsupported schema_version {version!r}; expected {SCHEMA_VERSION}"
            )
        if data.get("schema_name") != SCHEMA_NAME:
            raise ContractError(
                f"unexpected schema_name {data.get('schema_name')!r}; expected {SCHEMA_NAME!r}"
            )
        for required in ("export_id", "source_device_id", "export_created_at"):
            if required not in data:
                raise ContractError(f"bundle is missing '{required}'")
        raw_messages = data.get("messages")
        if not isinstance(raw_messages, list):
            raise ContractError("'messages' must be a list")
        return ExportBundle(
            export_id=str(data["export_id"]),
            source_device_id=str(data["source_device_id"]),
            export_created_at=int(data["export_created_at"]),
            messages=[ExportedMessage.from_dict(m) for m in raw_messages],
            schema_version=int(data["schema_version"]),
            schema_name=str(data["schema_name"]),
            cursor=data.get("cursor"),
        )


def serialize(bundle: ExportBundle) -> bytes:
    """Canonical JSON serialisation, for hashing and for the encrypted payload."""
    return json.dumps(
        bundle.to_dict(), sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def parse(raw: bytes) -> ExportBundle:
    """Parse and validate a bundle. Raises ContractError on any violation."""
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ContractError(f"bundle is not valid UTF-8 JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ContractError("bundle must be a JSON object")
    return ExportBundle.from_dict(data)


def private_only(bundle: ExportBundle) -> Iterable[ExportedMessage]:
    """The messages that belong in a private-message inbox."""
    return (m for m in bundle.messages if m.message_kind in PRIVATE_MESSAGE_KINDS)
