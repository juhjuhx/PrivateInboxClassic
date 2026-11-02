#!/usr/bin/env python3
"""Regenerate the fingerprint-input golden vectors.

`CryptoStore.fingerprintInput` is a pure function:

    base = f"{package}\\0{timestamp}\\0{title}\\0{text}"
    data = base if discriminator == "" else f"{base}\\0{discriminator}"

This script reproduces it exactly and prints the UTF-8 hex of each input, which
is what `LegacyFingerprintCompatibilityTest` pins. The HMAC key lives in the
Android Keystore and is per-device, so only the INPUT bytes are portable.

Provenance of the formula pinned here
-------------------------------------
Source commit : bb36c83f524c0aa87ba04680f0f4c0bfea79318f
Source file   : app/src/main/java/tw/personal/privateinbox/CryptoStore.kt
Verified      : `git diff HEAD -- CryptoStore.kt` is EMPTY, and
                `git show bb36c83:...CryptoStore.kt` has the identical body:

                    val base = "$packageName\u0000$timestamp\u0000$title\u0000$text"
                    val data = if (discriminator.isEmpty()) base
                               else "$base\u0000$discriminator"

The vectors below are produced from THAT formula, independently in Python, so the
Kotlin test is not "proving itself" — it is comparing two implementations of the
same historical spec.

Key portability
---------------
`fingerprint()` is HMAC-SHA256 over these bytes, keyed by an Android Keystore key
that is per-device and NOT portable. Therefore:
  * we pin the INPUT BYTES (portable, and they decide dedup), and
  * we never compare HMAC output across devices,
  * and we never hard-code a private key.

Run:  python3 docs/handoff/v046/golden/gen_fingerprint_vectors.py
"""

VECTORS = [
    ("ascii-basic", "org.telegram.messenger", 1700000000000, "Alice", "hello", ""),
    ("with-discriminator", "org.telegram.messenger", 1700000000000, "Alice", "hello",
     "c:Alice:hello:i0"),
    ("zero-timestamp", "jp.naver.line.android", 0, "家族群組", "你好", "c:Bob:你好:i1"),
    ("unicode-cjk", "jp.naver.line.android", 1700000000001, "家族群組", "你好，世界", ""),
    ("emoji", "com.whatsapp", 1700000000002, "Bob", "👍🏽", ""),
    ("empty-body", "com.whatsapp", 1700000000005, "Bob", "", ""),
    ("embedded-nul", "com.whatsapp", 1700000000006, "Bob", "a\u0000b", ""),
    ("newlines", "com.whatsapp", 1700000000004, "Bob", "line1\nline2", ""),
]


def fingerprint_input(pkg, ts, title, text, disc):
    base = f"{pkg}\u0000{ts}\u0000{title}\u0000{text}"
    return base if disc == "" else f"{base}\u0000{disc}"


if __name__ == "__main__":
    for name, pkg, ts, title, text, disc in VECTORS:
        raw = fingerprint_input(pkg, ts, title, text, disc)
        print(f"{name}: {raw.encode('utf-8').hex()}")
