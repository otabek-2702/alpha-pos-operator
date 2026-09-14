# Operator 2.0 validation

This file records the release checks. It is updated as each executable check
finishes; a compiled APK is not proof of Samsung-specific call recording access.

## Automated checks

- JavaScript/TypeScript: typecheck passed; nine pairing and configuration tests
  passed (multiple targets, rescanning, old URL migration, invalid QR rejection,
  WSS/IPv6, keeping bot secrets out of persisted JS view models, and private
  Telegram QR provisioning with separate groups, and incomplete native Telegram
  settings before setup). Controlled startup/read-failure checks also confirmed
  zero writes after a failed read and preservation of both POS entries after retry.
- Native recording policy: 17 JVM checks passed against production Kotlin code.
- APK signing: the local 2.0 test build and the previous local 1.0.0 APK use the
  same certificate. The previous APK is retained as `dist/operator-1.0.0.apk`.
  The final ARM release artifact still needs its own package/signing audit.
- Telegram setup API: separate report messages and a synthetic silent WAV
  document upload were confirmed. These checks verify bot/group access;
  they do not validate the Android app's recording pipeline.
- Android instrumentation passed on an independent Android 35 emulator:
  [validation run](https://github.com/otabek-2702/alpha-pos-operator/actions/runs/34896015393).
  Both candidate APK hashes were verified, both APKs installed successfully, and
  the custom runner returned its PASS result and instrumentation code -1.
  The executable checks cover real SQLite
  baseline/outbox persistence, Keystore encryption, uptime intervals, answer
  timing, call duration, missed/waiting calls, callback attempts vs connections,
  customer names, independent POS ACKs and restart recovery. Emulator UI and
  foreground-service lifecycle checks are being rerun on Android 13 and 15 after
  fixing a configuration-read failure found by the first UI run.
  The local API 35 emulator failed or stalled during Android startup and app
  installation under host memory pressure. A low-RAM configuration did not
  make it suitable for the required checks, so an independent CI emulator is
  being used. A requested watchdog setting was stored, but logs still showed
  a 60-second timeout; no effective 300-second timeout is claimed. These host
  attempts are not successful app tests or changes shipped in the APK.
- Desktop: full lint/typecheck passed; 207 tests passed, four existing SQLite
  cases skipped by the desktop test environment. Real WebSocket and UDP tests
  cover permanent pairing, saved mode, discovery, durable call records and ACKs.
- [Desktop 0.0.16 is published](https://github.com/otabek-2702/smart-pos-releases/releases/tag/v0.0.16).
  The downloaded public installer matches GitHub SHA256 and the updater's SHA512.
  The user approved including the existing
  kitchen-display changes. The full release source is committed as
  `2f8d7313c68ebb79df8f283c29028f4246dfd98d`; an isolated Operator-only branch
  remains as a backup. Installer/source hashes are recorded in the desktop
  release manifest.

## Physical Samsung check

The user confirmed **Samsung SM-A037F/DS, Android 13, one active SIM**. The
deployment serves **one Smart Food branch**, with one shared bot for the separate
recording and report groups. Multiple POS machines belong to this same branch.
These confirmed setup details are not evidence of a completed device test.

Follow the [Uzbek installation guide](operator-2-install-uz.md) before testing.

After installing, verify an answered incoming call, an unanswered incoming
call, an outgoing callback that connects, and a callback that is not answered.
Check the POS screens, separate report group, and original audio group. The
recording folder must be readable through Android's folder chooser.
Confirm the phone actually grants call-log access after the normal APK install;
Android classifies READ_CALL_LOG as a restricted permission whose availability
also depends on the installer. The app keeps missing permissions visible.

Then test with the screen locked, after removing the UI from recent apps,
after a normal restart and first unlock, and after disconnecting/reconnecting
Wi-Fi. Pending audio and completed-call records should survive. The desktop's
Operator setting and its pairing identity should survive logout and restart.

## Deliberate limits

- Android Force stop prevents automatic restart until the app is opened again.
  Samsung sleeping/deep-sleeping restrictions must be disabled for this app.
- First answer delay is observed only while call detection is running. The
  Android call log supplies final outcome and talk duration after downtime;
  unavailable answer/end timestamps stay unknown.
- Overlapping calls make individual end times ambiguous. The app records an
  observed overlap without claiming it caused the missed call.
- A nonzero outgoing call-log duration confirms a conversation; a zero-second
  outgoing entry is labelled unconfirmed, since a very short connection can
  round to zero. Callback delay is unknown if the missed-call end was not seen.
- Names depend on the POS's existing recent-orders lookup, currently limited
  to its latest 200 orders; no match means no invented customer name.
- Live caller popups are not replayed for finished calls after reconnection.
  Completed call records instead use durable revision ACKs.
- New audio waits for a stable file and no active call. Files larger than
  Telegram's 50 MB Bot API upload limit remain visibly failed.
- Audio setup excludes files already in the selected folder. Saving a folder,
  recording-group or bot-identity change, or saving audio off and then on,
  creates a new baseline and excludes the files present at that point, including
  previously pending files. Keep uploads enabled during temporary network loss
  so the existing pending queue can retry. Re-saving unchanged enabled settings
  keeps the baseline.
- Telegram has no idempotency key for sendDocument/sendMessage; a successful
  request with a lost response can rarely duplicate on retry.
- Service uptime reflects the Android service, not POS/network availability.
  Unexpected stopping times use the last heartbeat and are labelled estimated.

## Source references

- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android storage access framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android call-log permission](https://developer.android.com/reference/android/Manifest.permission#READ_CALL_LOG)
- [Telegram document uploads](https://core.telegram.org/bots/api#senddocument)
