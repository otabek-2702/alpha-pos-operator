# Smart POS Operator 2.0

Android operator app with persistent connections to multiple desktop POS machines,
new-recording uploads, and call reports. The interface defaults to Uzbek.

## Set up once

1. Install the new Android APK and grant phone/call-log, camera, notification,
   file access and battery permissions from **Ruxsatlar va batareya**.
2. On each desktop, tap **Operator** to enable receiving. Hold the button to show
   its QR. On the phone choose **POS qo‘shish** and scan it once.
3. The phone remembers every POS and sends each call to all saved machines. Use
   **Sinov yuborish** to check connected POS screens; remove individual POS rows
   with **O‘chirish**.
4. Open **Telegram yozuvlari va hisobot**, import the private Telegram setup QR
   or enter the bot token and two group IDs. With All files access granted the app
   auto-detects the Samsung recordings folder (`Recordings/Call`); an in-app
   folder browser and Android's chooser are fallbacks. Enable audio uploads and save.
5. Calls/metrics go to **Smart Food qo'ng'iroqlar ma'lumotlari**; audio files go
   to **Smart Food ovoz yozuvlari**. Only recordings created after initial setup
   are uploaded. Existing files are excluded.

The desktop saves its mode and permanent pairing credentials independently of
cashier login. A newer desktop QR includes stable machine identity, allowing
UDP discovery to recover changed LAN addresses. The POS app must be running,
and both devices need a reachable local network. An upgrade from old rotating
credentials may need one initial rescan.

## Operator 2.2: reports, shifts, managers and POS roles

- **Telegram outbox** (`OperatorTelegram.kt`): one durable row per message and
  chat. A new call revision edits the sent message; deleted messages are sent
  again; every message is mirrored to the optional backup group; basic groups
  upgraded to supergroups are followed (`migrate_to_chat_id`).
- **Call lifecycle** (`OperatorSupervisor.kt`, `OperatorReports.kt`): answered /
  missed / resolved / lost / closed-hours / blocked states with Uzbek hashtags.
  No callback within 1 minute alerts the managers on duty (SMS + Telegram DM,
  once per waiting client), 5 minutes marks the client lost. A callback in
  progress postpones both.
- **Shifts** (`OperatorSchedule.kt`): default 08:00–17:00 and 17:00–02:00; a
  pinned report after each shift; closed-hours callers can get one SMS per
  closed period (Uzbek mobiles only, daily cap). Managers rotate weekly from
  Sunday or keep a fixed shift and connect to the bot with a private
  `/start` link. Bot commands: `/holat`, `/hisobot`, `/raqam`.
- **Health alerts**: POS offline for 5 minutes during a shift, internet outage,
  low battery or unplugged charger, answered call without a recording.
- **POS roles** (protocol 3): the phone sends `operator_hello` with the role and
  `call_state` snapshots (current + recent calls). Operator POS opens the call
  dialog only when no order is being entered; cashier POS never opens it; both
  offer quick-fill caller chips. POS 0.0.17+ sends `order_created` back so calls
  link to orders. 2.1 phones and older desktops keep the legacy
  `call_start`/`call_end` frames.

## Background operation and records

The Android foreground service owns call detection, sockets, reconnects, file
scanning and uploads. It runs independently of the React screen, restarts after
normal phone boot/unlock and package updates, and continues when the UI is
swiped away. Android **Force stop** and Samsung's own sleeping-app controls can
still stop it; the system does not allow an ordinary app to override Force stop.
The permissions screen checks actual battery/file grants. On Samsung, also
exclude Operator from sleeping/deep-sleeping apps.

**Ishlash tarixi** records service periods. An unexpected shutdown uses the last
heartbeat as an approximate end; it does not invent an exact shutdown time.

Call records retain observed answer delay, Android call-log talk duration,
missed/rejected outcomes, observed overlap with another call, and callback
attempts versus confirmed conversations. Missing/ambiguous timestamps are null.
Outgoing dialing alone does not prove a connection. Callback updates edit the
original Telegram report. Customer names come from the POS's existing recent
orders lookup; unmatched callers remain unnamed.

Audio and reports use durable SQLite queues. Pairing credentials and the bot
token are encrypted using Android Keystore. Tokens are not bundled into the APK.
The app uploads original audio as Telegram documents (maximum 50 MB), waiting
for stable files and idle calls. Telegram does not support upload idempotency:
a rare accepted upload whose response is lost can appear twice on retry.

## Source and verification

- `App.tsx`, `src/operator.ts`, `src/operator-model.ts`: configuration, validation,
  legacy migration and native runtime bridge.
- `src/screens/`: simplified home, scanner, real permission status, Telegram and
  service-period history.
- `plugins/operator-native/`: native service, boot receiver, encrypted settings,
  recording queue and call ledger. These templates are copied during prebuild;
  edit them instead of generated `android/` files.
- `tests/operator-model.test.cjs`: pairing, migration and credential redaction.
- `scripts/test-native-policy.ps1`: executable Kotlin recording policy checks.
- `tests/android/`: actual Android SQLite, Keystore and synthetic call-log checks.
- `scripts/telegram-setup.mjs`: local bot/group verification and branding; reads
  ignored `.env.telegram`. Never commit its token or private provisioning QR.

```powershell
npm run typecheck
node --test tests/operator-model.test.cjs
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-native-policy.ps1
```

Native services require a standalone APK or Expo development build. Expo Go and
iOS do not provide this Android operator runtime. See
[the release and phone test notes](docs/operator-2-validation.md) for validation
results and the remaining Samsung-specific checks.

---
## Releasing updates (automatic on the phones)

Phones on 2.1.0+ check `https://github.com/otabek-2702/smart-pos-operator-releases`
every 30 minutes. A newer release is downloaded, verified (SHA-256 from
`update.json`, package name, versionCode, signing certificate) and installed by
Android without a prompt (`USER_ACTION_NOT_REQUIRED`; the phone must allow
"Install unknown apps" for Operator). Installation waits until no call has been
active for a minute. After the restart the service resends every unsent
recording and report immediately and posts the result to the report group.

```powershell
npm run release -- -Version 2.1.1 -Notes "What changed" -Publish
```

The script sets the version (versionCode = major*10000 + minor*100 + patch),
builds, re-signs, verifies, audits the APK for private values, writes
`update.json` and publishes the GitHub release. Omit `-Publish` for a local
build in `dist/release-<version>/`.

**Signing.** Up to 2.0.1 the APK was signed with the public React Native
template debug key. From 2.1.0 it is signed with a private key plus an APK
Signature Scheme v3 rotation lineage, so phones updated in place and keep their
data; the legacy key can no longer sign updates. It keeps the lineage
"permission" capability (Android rejects the update otherwise, because AndroidX
declares a signature permission owned by the old key). After every phone runs a
rotated build, run `scripts/operator-signing.ps1 -Action revoke` before the next
release to drop it (done for 2.2.0; phones still on 2.0.x must reinstall). Keys are created once with
`scripts/operator-signing.ps1 -Action create -Key release` and live in
`%USERPROFILE%\.smart-pos-operator-signing`. **Back that folder up offline** —
without it no installed phone can be updated again. The `ci` key is for emulator
tests only (`.github/workflows/android-update-validation.yml`).

## Prerequisites

- Node.js 18+ and npm.
- An Expo account: `npx expo register` or `npx expo login`.
- EAS CLI: `npm install -g eas-cli` then `eas login`.
- An Android device (a dedicated operator phone) with USB debugging, or an
  Android emulator. The device and the desktop POS must be on the **same LAN**.

---

## Build & run (EAS dev build — recommended)

```bash
# 1. Install dependencies
npm install

# 2. Link the project to your Expo account / EAS (creates the project on first run)
eas init

# 3. Build the Android dev client in the cloud (APK, installable on the device)
eas build --profile development --platform android
```

When the build finishes, EAS prints a URL/QR. Install the APK on the operator
phone (open the link on the device, or `eas build:run -p android` to install the
latest build, or `adb install <file>.apk`).

Then start the bundler and connect the dev client:

```bash
# 4. Start Metro for the dev client
npx expo start --dev-client
```

Open the installed **Operator** dev build on the phone; it
connects to Metro over the LAN (or scan the QR shown in the terminal). Grant the
camera and phone permissions when prompted, then scan the POS pairing QR.

### Build with your account, no login prompt (token)

For repeatable builds without typing your password each time, authenticate with
an Expo access token instead of an interactive login:

1. Create a token at <https://expo.dev/settings/access-tokens> → **Create
   token** (a Personal Access Token — treat it like a password).
2. Copy `.env.example` to `.env` and paste the token:

   ```
   EXPO_TOKEN=your_token_here
   ```

   `.env` is git-ignored — it never leaves your machine and is never committed.
3. Link the project once (uses the token, no password prompt):

   ```bash
   npx dotenv -e .env -- eas init
   ```

4. Build via the npm scripts (each loads `.env` then runs EAS non-interactively):

   ```bash
   npm run build:preview   # standalone APK, runs without Metro (operator phone)
   npm run build:dev       # dev client (needs `npx expo start --dev-client`)
   npm run build:prod      # production app bundle (.aab)
   ```

The first build auto-generates EAS-managed Android signing credentials — no
keystore setup required.

### Fast standalone APK on Windows (no EAS queue)

With JDK 17 and the Android SDK installed, build locally:

```powershell
npm run build:apk
```

The script finds JDK 17 in the Gradle JDK cache when `JAVA_HOME` is unset,
uses `ANDROID_HOME` (or the standard Windows SDK location), refreshes the
native project without cleaning, and runs a release build with Gradle caching.
The first build downloads missing Gradle/SDK/Maven components; later builds
reuse them. One Gradle worker and one native compilation job limit memory use
on this PC.

Output: `dist/operator.apk`. It includes the JavaScript bundle and runs without
Metro. The default APK includes both `arm64-v8a` and `armeabi-v7a`, covering
64-bit and older 32-bit ARM phones. For an emulator-only build:

```powershell
npm run build:apk -- -Architectures 'x86_64'
```

Packaging filters to the requested architectures and checks that each contains
the required native libraries before copying the APK to `dist`.

The generated project's release build uses the local debug signing key by
default, suitable for internal testing. Updating an existing EAS-signed install
requires its matching signing key; see Expo's local release signing guide:
<https://docs.expo.dev/guides/local-app-production/>. This local script does not
apply EAS profile settings such as the `preview` OTA update channel.

For a cloud-built, EAS-signed standalone APK, use the existing `preview` profile
(`production` creates an `.aab`, and `development` needs Metro):

```powershell
npx --yes eas-cli build --profile preview --platform android
```

Authenticate with `eas login`, or load the existing `.env` token without printing
it (the npm `build:preview` script does this when `eas-cli` is installed):

```powershell
.\node_modules\.bin\dotenv.cmd -e .env -- npx --yes eas-cli build --profile preview --platform android --non-interactive
```

As checked September 12, 2026, Expo Free includes 15 Android builds per month
in a low-priority queue. Queue time varies, so local builds with cached tools
are the more predictable choice for repeated builds:
<https://expo.dev/pricing>.

For interactive development with a connected device/emulator instead:

```powershell
npx expo run:android
```

The `android/` directory is generated and git-ignored. Edit `app.json` and the
config plugin for persistent native changes. Avoid `prebuild --clean` for
routine builds because it discards the generated project's incremental cache.

---

## Testing the protocol without the real POS

Start the two local emulator POS peers:

```powershell
node scripts/operator-mock.mjs
```

The test helper uses TCP 8765/8767 with fake test credentials, acknowledges
completed-call records and returns a synthetic customer name. It logs event
types without credentials or phone numbers. The emulator scenario runner can
configure both targets using Android's host alias `10.0.2.2`.

---

## Troubleshooting

- **Caller number is empty.** `READ_CALL_LOG` was not granted. Both
  `READ_PHONE_STATE` **and** `READ_CALL_LOG` are mandatory on Android 9+. Grant
  both in system settings and relaunch.
- **Cannot connect to `ws://…`.** The app and desktop must be on the same LAN,
  the desktop server must be listening on the QR's port, and the firewall must
  allow it. Cleartext `ws://` is enabled via `usesCleartextTraffic` in
  `app.json`.
- **Detection stops after the screen locks.** Confirm the persistent
  "Smart POS Operator" notification is present (that is the foreground
  service). On aggressive OEM ROMs (Xiaomi, Huawei, Samsung), disable battery
  optimization / enable "autostart" for the app.
- **Nothing happens in Expo Go.** Expected — call detection and the foreground
  service are native. Use the dev build.
- **`new WebSocket` rejected on a release build.** Ensure
  `usesCleartextTraffic: true` (already set) survived prebuild, or pair to a
  `wss://` endpoint.
