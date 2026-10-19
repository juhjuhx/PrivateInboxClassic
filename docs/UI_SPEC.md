# UI Specification: Telegram Android 2.0 / Material One

This is an original UI inspired by Telegram's historical 2014-2016 interaction patterns, not copied source code or assets.

- Screen density: Android dp / sp. Color `#5682A3` (header blue), `#426787` (status bar), `#17212B` (titles), `#8595A4` (secondary), `#DCE5ED` (chat background), white (chat bubble), `#70B76E` (count badge).
- Toolbar: 56 dp, shadow, compact title at 20sp, 52 dp leading action, 54 dp search tap target.
- Chat list: white 76dp rows, avatar circle 54dp, 16sp name + 14sp one-line preview, time at 11sp, thin separators. Green badge shows CAPTURED NOTIFICATION COUNT, not official unread count.
- Conversation: incoming-only bubble, light blue-gray background, 15.5sp message text, image 230×170dp preview, tap to view image fullscreen. Read-only footer explicitly says it cannot send messages or change read receipts.
- Privacy screens: credential lock on entry, settings with separate capture/attachment toggles; safe defaults off.
- Screens: lock / inbox / thread / photo viewer / search / settings.
- Data: UI only displays rows loaded after unlocking. No send, reply, forwarding, gallery/media permission, cloud account, network or debug logs.
- Accessibility improvement backlog: TalkBack labels on all controls, 200% font scaling for bubble width, RTL checks, contrast testing and multiwindow resizing.

Visual reference: https://telegram.org/blog/android-2-0. No Telegram trademark or branded images bundled.
