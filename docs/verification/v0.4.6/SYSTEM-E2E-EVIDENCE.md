# v0.4.6 — System Notification E2E Evidence (T05)

## Test level taxonomy (per brief)
| Level | Scope | Status |
|---|---|---|
| A. Direct capture integration | `capture()` driven by tests | **PASS** — CapturePipelineIntegrationInstrumentedTest 9/9 |
| B. Android system delivery | REAL NotificationManager post → system_server → listener bind → production pipeline | **PASS** — SystemNotificationE2EInstrumentedTest 1/1 |
| C. Activity UI rendering | REAL MainActivity through ActivityScenario | **PASS** (asserted inside B, and ConversationUiLifecycleInstrumentedTest 6/6) |
| D. Real third-party apps / OEM | LINE / Messenger / Threads on hardware | **REAL_DEVICE_NOT_TESTED** — no device with those apps was available |

## What B actually exercises (no shortcuts)
`SelfTestNotifier.post(...)` → devtest app's own `NotificationManager.notify()` →
**Android system_server notification pipeline** → the bound
`PrivateNotificationListener.onNotificationPosted` (verified via the in-process
`ensureListener` connected flag) → production parser → worker queue → AES-GCM
save → canonical resolve → `message_conversation` link → REAL `MainActivity`
via ActivityScenario.

The posted package IS the devtest app (`tw.personal.privateinbox.devtest`) via
its registered self-test source. It is **never claimed** to be a LINE/Telegram
notification. No INTERNET/Accessibility/root permissions were added.

## Assertions verified in the single B test
1. System notification posted and delivered to the listener.
2. Message committed through the production write path.
3. Canonical link created by the listener itself (`cv_…` present).
4. REAL MainActivity renders exactly one box.
5. Activity relaunch keeps the history.
6. A second notification with a RENAMED title but the SAME shortcut lands in the
   same canonical conversation (conversation count stays 1).
7. A notification with a DIFFERENT shortcut and the same title creates a second
   conversation (count = 2).

## Harness additions
- `SelfTestNotifier.post(...)` gained an overload (title/shortcut/id) with the
  historical fixed notification preserved as the default path.
- `DebugSettingsProvider` gained `postSelfTest` / `cancelSelfTest` (debug build
  only), so the test can post through the APP's own NotificationManager from the
  instrumentation process.

## Gaps
- A dedicated second-package fixture app was considered; the installed
  `tw.personal.privateinbox.burstposter` has no source in this repo and cannot be
  used to attribute notifications to LINE/Telegram. The self-test source is the
  sanctioned identity for B.
- Listener reconnection / removed-notification scenarios for B are partially
  covered by ListenerRobustnessInstrumentedTest (listener side) and the pipeline
  suite (reconcile); a B-level reconnection test is future work.
