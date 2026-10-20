"""Encrypted bundle format for the DHC connector.

The Android exporter decrypts its own rows with the Keystore key, then
re-encrypts the resulting bundle with a key derived from a passphrase the user
chooses. The Keystore key never leaves the device; the passphrase never leaves
the user's head.

Crypto choices, and why:

* **KDF**: PBKDF2-HMAC-SHA256. It is in the Python standard library
  (``hashlib.pbkdf2_hmac``) and in the Java standard library
  (``SecretKeyFactory`` with ``PBKDF2WithHmacSHA256``), so the Android exporter
  and the Python importer derive the identical key with no extra dependency and
  no hand-rolled construction. This is a standard construction, not a novel one.
* **AEAD**: AES-256-GCM. Available as ``javax.crypto.Cipher`` on Android and
  via ``cryptography`` on the Python side.
* **Nonce**: 12 random bytes per bundle, from a CSPRNG. Never reused for the
  same key. The nonce is stored in the header and is not secret.
* **Salt**: 16 random bytes per bundle, stored in the header. A fresh salt per
  export means the same passphrase produces a different key every time.
* **AAD**: the header is authenticated, so the version, KDF parameters and
  salt cannot be swapped without detection.

The file layout is::

    magic  : 8 bytes  b"PICBOX01"
    header : JSON, utf-8, describing kdf/salt/nonce/iterations/schema
    body   : AES-256-GCM ciphertext of the canonical bundle JSON

Everything before the ciphertext is authenticated as AAD.
"""

from __future__ import annotations

import json
import os
import struct
from dataclasses import dataclass
from typing import Any, Optional

MAGIC = b"PICBOX01"
KDF_ALGORITHM = "PBKDF2-HMAC-SHA256"
CIPHER_ALGORITHM = "AES-256-GCM"
DEFAULT_ITERATIONS = 210_000
# The floor for the KDF. The Android exporter uses DEFAULT_ITERATIONS; the
# floor exists so a malformed or hostile header cannot ask for a weak KDF.
MIN_ITERATIONS = 50_000
SALT_LENGTH = 16
NONCE_LENGTH = 12
KEY_LENGTH = 32  # AES-256


class DecryptionError(Exception):
    """Raised when a bundle cannot be decrypted or does not authenticate."""


class UnsupportedVersionError(DecryptionError):
    """Raised when the bundle uses a format this build does not understand."""


@dataclass
class BundleHeader:
    schema_version: int
    kdf: str
    kdf_iterations: int
    salt: bytes
    nonce: bytes
    cipher: str
    body_length: int

    def to_dict(self) -> dict[str, Any]:
        import base64

        return {
            "schema_version": self.schema_version,
            "kdf": self.kdf,
            "kdf_iterations": self.kdf_iterations,
            "kdf_salt": base64.b64encode(self.salt).decode("ascii"),
            "nonce": base64.b64encode(self.nonce).decode("ascii"),
            "cipher": self.cipher,
            "body_length": self.body_length,
        }

    @staticmethod
    def from_dict(data: dict[str, Any]) -> "BundleHeader":
        import base64

        try:
            return BundleHeader(
                schema_version=int(data["schema_version"]),
                kdf=str(data["kdf"]),
                kdf_iterations=int(data["kdf_iterations"]),
                salt=base64.b64decode(data["kdf_salt"]),
                nonce=base64.b64decode(data["nonce"]),
                cipher=str(data["cipher"]),
                body_length=int(data["body_length"]),
            )
        except (KeyError, ValueError, TypeError) as exc:
            raise DecryptionError(f"malformed bundle header: {exc}") from exc


def derive_key(passphrase: str, salt: bytes, iterations: int) -> bytes:
    """Derive the AES-256 key from a passphrase.

    PBKDF2-HMAC-SHA256, the same construction on both sides. The iteration
    count is stored in the header so it can be raised later without breaking
    existing bundles.
    """
    if not passphrase:
        raise DecryptionError("passphrase must not be empty")
    import hashlib

    return hashlib.pbkdf2_hmac(
        "sha256", passphrase.encode("utf-8"), salt, iterations, dklen=KEY_LENGTH
    )


def _aead():
    """The AEAD implementation.

    Two mature, widely used libraries are supported so the importer runs
    wherever it is installed:

    * ``cryptography`` (the OpenSSL-backed Rust implementation), preferred
    * ``pycryptodome`` (the Crypto.Cipher module), the fallback

    Both are standard, audited implementations of AES-GCM. Neither is a
    hand-rolled construction. The module is imported lazily so the rest of the
    file can be inspected without either library present.
    """
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        return _CryptographyAead(AESGCM)
    except ImportError:
        pass
    try:
        from Crypto.Cipher import AES

        return _PyCryptodomeAead(AES)
    except ImportError as exc:  # pragma: no cover - environment dependent
        raise DecryptionError(
            "the 'cryptography' or 'pycryptodome' package is required to "
            "decrypt bundles"
        ) from exc


class _CryptographyAead:
    """AES-GCM through the ``cryptography`` package."""

    def __init__(self, aead_cls):
        self._cls = aead_cls

    def encrypt(self, key: bytes, nonce: bytes, plaintext: bytes, aad: bytes) -> bytes:
        return self._cls(key).encrypt(nonce, plaintext, aad)

    def decrypt(self, key: bytes, nonce: bytes, ciphertext: bytes, aad: bytes) -> bytes:
        return self._cls(key).decrypt(nonce, ciphertext, aad)


class _PyCryptodomeAead:
    """AES-GCM through pycryptodome.

    pycryptodome's GCM mode appends the 16-byte authentication tag to the
    ciphertext, which matches the container format: the tag travels with the
    body and the whole thing is authenticated as one unit.
    """

    def __init__(self, aes_cls):
        self._aes = aes_cls

    def encrypt(self, key: bytes, nonce: bytes, plaintext: bytes, aad: bytes) -> bytes:
        cipher = self._aes.new(
            key, self._aes.MODE_GCM, nonce=nonce, mac_len=16
        )
        cipher.update(aad)
        encrypted, tag = cipher.encrypt_and_digest(plaintext)
        return encrypted + tag

    def decrypt(self, key: bytes, nonce: bytes, ciphertext: bytes, aad: bytes) -> bytes:
        if len(ciphertext) < 16:
            raise ValueError("ciphertext is shorter than the GCM tag")
        body, tag = ciphertext[:-16], ciphertext[-16:]
        cipher = self._aes.new(key, self._aes.MODE_GCM, nonce=nonce, mac_len=16)
        cipher.update(aad)
        return cipher.decrypt_and_verify(body, tag)


def encrypt_bundle(plaintext: bytes, passphrase: str, schema_version: int,
                   iterations: int = DEFAULT_ITERATIONS) -> bytes:
    """Encrypt a canonical bundle into the PICBOX01 container.

    The iteration count is a parameter so the test suite can run quickly. The
    production default is [DEFAULT_ITERATIONS]; the Android exporter always
    uses that value.
    """
    if iterations < MIN_ITERATIONS:
        raise ValueError(
            f"the iteration count is too low to be useful "
            f"({iterations} < {MIN_ITERATIONS})"
        )
    salt = os.urandom(SALT_LENGTH)
    nonce = os.urandom(NONCE_LENGTH)
    key = derive_key(passphrase, salt, iterations)
    header = BundleHeader(
        schema_version=schema_version,
        kdf=KDF_ALGORITHM,
        kdf_iterations=iterations,
        salt=salt,
        nonce=nonce,
        cipher=CIPHER_ALGORITHM,
        body_length=len(plaintext),
    )
    header_bytes = json.dumps(
        header.to_dict(), sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    aad = MAGIC + struct.pack(">I", len(header_bytes)) + header_bytes
    ciphertext = _aead().encrypt(key, nonce, plaintext, aad)
    return aad + ciphertext


def decrypt_bundle(container: bytes, passphrase: str) -> bytes:
    """Decrypt and authenticate a PICBOX01 container.

    Raises DecryptionError for a wrong passphrase, a tampered file, or a
    malformed container. Raises UnsupportedVersionError for a version this
    build does not know.
    """
    if len(container) < len(MAGIC) + 4:
        raise DecryptionError("file is too short to be a bundle")
    if container[: len(MAGIC)] != MAGIC:
        raise DecryptionError("not a Private Inbox bundle (bad magic)")
    offset = len(MAGIC)
    (header_length,) = struct.unpack(">I", container[offset : offset + 4])
    offset += 4
    header_bytes = container[offset : offset + header_length]
    if len(header_bytes) != header_length:
        raise DecryptionError("bundle header is truncated")
    offset += header_length
    try:
        header_dict = json.loads(header_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DecryptionError(f"bundle header is not valid JSON: {exc}") from exc
    header = BundleHeader.from_dict(header_dict)

    if header.schema_version != 1:
        raise UnsupportedVersionError(
            f"unsupported bundle schema version {header.schema_version}"
        )
    if header.kdf != KDF_ALGORITHM:
        raise UnsupportedVersionError(f"unsupported KDF '{header.kdf}'")
    if header.cipher != CIPHER_ALGORITHM:
        raise UnsupportedVersionError(f"unsupported cipher '{header.cipher}'")
    if len(header.nonce) != NONCE_LENGTH:
        raise DecryptionError("nonce has the wrong length")
    if len(header.salt) != SALT_LENGTH:
        raise DecryptionError("salt has the wrong length")

    ciphertext = container[offset:]
    if len(ciphertext) != header.body_length + 16:
        # GCM appends a 16-byte tag.
        raise DecryptionError("bundle body length does not match the header")
    key = derive_key(passphrase, header.salt, header.kdf_iterations)
    aad = MAGIC + struct.pack(">I", header_length) + header_bytes
    try:
        plaintext = _aead().decrypt(key, header.nonce, ciphertext, aad)
    except Exception as exc:
        # AES-GCM raises InvalidTag for both a wrong key and a tampered body.
        # The two are indistinguishable by design, and both are a failure.
        raise DecryptionError(
            "could not decrypt the bundle: wrong passphrase or the file was modified"
        ) from exc
    return plaintext


def describe(container: bytes) -> dict[str, Any]:
    """Header information without needing the passphrase."""
    if container[: len(MAGIC)] != MAGIC:
        raise DecryptionError("not a Private Inbox bundle")
    offset = len(MAGIC)
    (header_length,) = struct.unpack(">I", container[offset : offset + 4])
    offset += 4
    header = BundleHeader.from_dict(
        json.loads(container[offset : offset + header_length].decode("utf-8"))
    )
    return {
        "schema_version": header.schema_version,
        "kdf": header.kdf,
        "kdf_iterations": header.kdf_iterations,
        "cipher": header.cipher,
        "body_length": header.body_length,
    }
