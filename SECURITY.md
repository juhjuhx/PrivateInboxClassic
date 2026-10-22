# Security Policy

Private Inbox Classic stores copies of messaging notifications **encrypted on
the device**. Please read this before reporting a problem.

---

## Reporting a vulnerability

**Do NOT open a public issue for a security problem.**

Report it privately to the maintainers. Include:

| What to include | Why |
|-----------------|-----|
| The app version (`Settings → About`) | The fix may already exist |
| The Android version and OEM | The behaviour is device-specific |
| What you observed | The reproduction |
| **Sanitized diagnostics** (`Settings → Diagnostics → Export`) | The stage codes and error types |
| Whether the device is rooted | A rooted device has a different threat model |

**NEVER include**: message content, sender names, conversation titles, image
bytes, or an unredacted diagnostics export. The sanitized export contains
package names, stage codes, timestamps and error type names only, and even
that reveals your usage pattern, so treat it as private.

You can expect an acknowledgement. Fix timing depends on severity.

---

## The security model

### What the app protects

| Property | How |
|----------|-----|
| Message content at rest | AES-256-GCM, key in the Android Keystore |
| Message fingerprints | HMAC-SHA256 (not a plain hash, so low-entropy text cannot be dictionary-attacked) |
| Screenshots | `FLAG_SECURE` in every build type, unconditionally |
| Diagnostics | Timestamps, stage codes, package names, error type names. **Never** content |
| Network | There is **no** network permission. The app cannot phone home. |
| The Keystore key | It never leaves the device. Export re-encrypts with a passphrase-derived key. |

### What the app does NOT protect against

| Threat | Why it is out of scope |
|--------|------------------------|
| A rooted device | The attacker can read the Keystore and the database directly |
| A malicious keyboard / accessibility service | It sees the screen before the app does |
| Someone holding the unlocked device | The app lock is the boundary, and the OS is the real one |
| Memory forensics of a running process | The plaintext exists in RAM while the screen is open |
| A compromised OS | The Keystore and the notification APIs are the OS's to protect |

### The deliberate design decisions

- **No `INTERNET` permission.** The CI job `Audit the built APK permissions`
  fails the build if it appears.
- **No tracking, analytics, advertising or Firebase.** The only runtime
  dependency is `androidx.activity`.
- **The app is not a chat client and not a relay.** It reads only what
  Android's notification system delivers. It cannot send anything.
- **Migrations are additive.** A schema upgrade never deletes or rewrites a
  row the user would lose.

---

## The known limits of notification capture

These are platform properties, not app bugs, and they are stated so nobody
reports them as vulnerabilities:

| Limit | Detail |
|-------|--------|
| The 25-message history cap | `Notification.MessagingStyle` carries at most 25 messages. A single revision with more is truncated by the **platform**. |
| The app's own notifications are not delivered to it | The platform excludes them. |
| The source decides what to show | If the source app never posts the message, the archiver cannot see it. |
| The notification must exist | A message that was never delivered as a notification was never captured. This app **does not** reconstruct chat history. |
| Retracted messages are kept | That is the **purpose**. A retraction in the source app does not remove the copy. |

---

## What this app deliberately does not do

- It does not bypass any Android security control.
- It does not require Root, Accessibility Service, or a sandbox bypass.
- It does not read other apps' data except through the notification listener
  API, which the user grants explicitly.
- It does not modify global system settings to keep itself alive. Where an
  OEM's background policy interferes, the app **tells the user where to
  change it** and leaves the choice to them.
