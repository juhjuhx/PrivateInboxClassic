# v0.4.6 Baseline — verified by reading the actual tree

Verified 2026-10-24, on `v045-reliability` @ `bb36c83f524c0aa87ba04680f0f4c0bfea79318f`.

## Tool preflight (Phase 0.1) — PASS
- `pwd`, `git status --short`, `git rev-parse HEAD` all executed successfully.
- Bash / Read / Write / Edit available. Gradle 8.13, JDK 17, Android SDK, adb,
  emulator-5554 (API 36) all available via `scripts/_env.sh`.
- Remote: `origin https://github.com/juhjuhx/PrivateInboxClassic.git`.

## Dirty worktree (preserved, NOT reset/cleaned)
```
 M app/src/androidTest/java/tw/personal/privateinbox/BurstCaptureInstrumentedTest.kt
 M app/src/main/java/tw/personal/privateinbox/CaptureDiagnostics.kt
 M app/src/main/java/tw/personal/privateinbox/InboxDatabase.kt
 M app/src/main/java/tw/personal/privateinbox/PrivateNotificationListener.kt
?? CHECKPOINT-0.md CHECKPOINT-R1.md CHECKPOINT-R2.md
?? app/src/androidTest/java/tw/personal/privateinbox/BatchRetryFaultInjectionInstrumentedTest.kt
?? docs/handoff/  (v045 set + this v046 set)
```
This is the v0.4.5 reliability candidate, **uncommitted**. v0.4.6 work must not
destroy it.

## Actual SQLite version
`InboxDatabase` is at **version 5** (`SQLiteOpenHelper(appContext, "inbox.db", null, 5)`),
not "assume v5" — confirmed by reading line 19. `onUpgrade` is additive with
`columnExists`/`addColumnIfMissing` guards (P0-A). Current columns include
`conversation_key`, `message_kind`, `source_ts`, `fingerprint`, `sealed_content`,
`sealed_image`, `received_at`, `package_name`.

## CONFIRMED defect — ConversationIdentity.key embeds the mutable title

Source: `ConversationIdentity.kt` lines 34–41.

```kotlin
val key: String
    get() = buildString {
        append(packageName)
        append('\u0000')
        append(shortcutId?.takeIf { it.isNotBlank() } ?: title)   // ← prefers shortcut
        append('\u0000')
        append(title)                                            // ← ALWAYS appends title
    }
```

**Even when a stable `shortcutId` exists, the display title is appended to the
key.** A contact renaming themselves, or a source app changing its
notification title, produces a DIFFERENT key for the SAME conversation, so the
inbox splits one chat room into two boxes.

This is not hypothetical: the key format is `pkg\0shortcut\0title`, and `title`
is appended unconditionally. Phase 1's premise is confirmed by the source.

## CONFIRMED defect — fingerprint depends on conversation_key

Source: `InboxDatabase.save()` (~line 229):
```kotlin
val conversationPart = conversationKey.ifEmpty { title }
val fingerprint = CryptoStore.fingerprint(packageName, dedupTs,
    conversationPart, "$sender\u0000$body", messageDiscriminator)
```

**Any migration that rewrites `conversation_key` changes the fingerprint of every
affected row.** That silently breaks:
- dedup (a re-posted message is no longer recognised → double row), and
- the "same message on two devices" identity used by DHC merge.

Therefore the conversation-identity change must be designed so that the
**fingerprint-relevant part of the key does not change for already-stored rows**.

## CONFIRMED defect — ImageCapture.readBytes() timeout cannot interrupt a blocking read

Source: `ImageCapture.kt` lines 61–77. The deadline is checked *before*
`input.read(buffer)`, so a provider that never returns keeps the read (and the
notification callback) blocked indefinitely. The loop is bounded by
`MAX_INPUT_BYTES` only if the stream keeps delivering data. Phase 3 must fix
this without adding permissions.

## DHC export (existing, reusable)
`app/src/main/java/tw/personal/privateinbox/dhc/` contains
`DhcExportService.kt`, `DhcExportPlan.kt`, `DhcExporter.kt`, plus
`integrations/dhc`. Phase 2 must reuse this contract, not invent a second format.

## Skills situation (honest)
- `using-superpowers` was supplied inline for this turn and is being followed
  (route packet, evidence-first, no fake tool calls).
- The Addy Osmani series is NOT installed as loadable skills in this session's
  catalog. Only a subset of the names the brief listed exist locally
  (`code-review-excellence`, `systematic-debugging`, `test-driven-development`,
  `verification-before-completion`, `brainstorming`, `open-code-review`,
  `understand-*`, `dispatching-parallel-agents`, `deep-understanding`,
  `performance-optimization`, `security-and-hardening`, `documentation-and-adrs`,
  `ci-cd-and-automation`, `source-driven-development`, `spec-driven-development`,
  `deprecation-and-migration`, `incremental-implementation`,
  `code-simplification`, `git-workflow-and-versioning`, `shipping-and-launch`,
  `writing-plans`, `observability-and-instrumentation`,
  `code-review-and-quality`). The brief's `SKILLS-MATRIX.md` will therefore mark
  each of the 24 topics as **LOCAL / REMOTE-ONLY / NOT-INSTALLED** with evidence,
  rather than claiming 24 loaded skills.
- Diagram tooling: no `mermaid`/`archify` execution path is available in this
  harness turn; `render_ui` with `mermaid` IS available and will be used for the
  roadmap diagram instead of claiming a skill ran.

## Not verified this phase
- Whether any installed APK on a real device uses cert `c876051e…` (needs a
  device with the app installed; emulator only has devtest debug builds).
- Whether a release keystore exists anywhere. `docs/SIGNING.md` to be read in
  Phase 4.
