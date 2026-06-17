# AlphaPOS Operator Link

An Android (React Native / Expo **dev build**) app for the operator phone that
answers and places client calls. It pairs to a desktop POS (Quasar / Electron)
by scanning a QR code, then streams the caller's phone number to that desktop
over the local network via WebSocket so the POS can pop the customer's open
orders or pre-fill a new order.

> **Android only for call reading.** iOS does not expose arbitrary incoming
> phone numbers to third-party apps (CallKit only surfaces VoIP calls your own
> app placed). The pairing/streaming UI builds on iOS, but native call-number
> detection is implemented and supported on Android only.

> **Not Expo Go.** Call detection and the foreground service are native modules.
> You must run a **custom dev build**, not Expo Go.

---

## How it works

1. **Pair** — the desktop POS displays a QR encoding
   `ws://<desktop-lan-ip>:8765?token=<token>`. The operator scans it. The URL
   is stored and the app connects to it verbatim (token included).
2. **Detect** — `react-native-call-detection` reports phone-state changes. The
   app infers call direction from the event sequence and emits protocol
   messages.
3. **Stream** — messages are sent over the WebSocket to the desktop, which is
   the server. The app reconnects automatically and buffers events during brief
   drops.
4. **Stay alive** — a foreground service keeps the process resident so call
   detection keeps working while the app is backgrounded on the dedicated
   operator phone.

The desktop URL persists (`AsyncStorage`), so on the next launch the app skips
the scanner and reconnects to the last paired desktop.

---

## Wire protocol

- The **desktop is the WebSocket server**; this app is the **client**.
- The QR encodes the full URL `ws://<desktop-lan-ip>:8765?token=<token>` and the
  app connects to it exactly as scanned.
- Messages the app **sends** (JSON, one per event — and nothing else):

  ```json
  { "type": "call_start", "phone": "<raw number from OS>", "direction": "in" }
  { "type": "call_start", "phone": "<raw number from OS>", "direction": "out" }
  { "type": "call_end",   "phone": "<raw number>" }
  ```

- The **raw** number is sent as reported by the OS. The desktop normalizes it.

### Android call-event mapping

`react-native-call-detection` reports `Incoming`, `Offhook`, `Disconnected`,
`Missed` on Android (with `readPhoneNumber=true` supplying the number).
Direction is inferred by tracking the sequence:

| OS event             | Condition                       | Action                              |
| -------------------- | ------------------------------- | ----------------------------------- |
| `Incoming` (+number) | —                               | send `call_start` `direction: "in"` |
| `Offhook` (+number)  | preceded by `Incoming`          | nothing (incoming call was answered)|
| `Offhook` (+number)  | **no** preceding `Incoming`     | send `call_start` `direction: "out"`|
| `Disconnected`/`Missed` | a call was being tracked     | send `call_end`, reset flags        |

---

## Project layout

```
App.tsx                                  # picks Pair vs Status screen by saved URL
index.ts                                 # registerRootComponent entry
app.json                                 # permissions + plugins (camera, build-props, foreground service)
eas.json                                 # EAS build profiles (development / preview / production)
plugins/withCallForegroundService.js     # config plugin: foreground service (manifest + Kotlin + autostart)
src/
  storage.ts                             # persist / load / clear the paired desktop URL
  hooks/useWebSocket.ts                  # connect, status, send(), backoff reconnect, offline buffer
  hooks/useCallBridge.ts                 # CallDetectorManager -> protocol messages
  screens/PairScreen.tsx                 # full-screen QR scanner (expo-camera, qr only)
  screens/StatusScreen.tsx              # connection status, address, re-pair, live event log
  types/call-detection.d.ts             # types for react-native-call-detection
```

---

## Permissions

Requested at runtime with rationale:

- **CAMERA** — scan the pairing QR (`expo-camera`).
- **READ_PHONE_STATE** and **READ_CALL_LOG** — **both** are required on
  Android 9+ to read the incoming caller number. Without `READ_CALL_LOG` the
  number comes back **empty**.
- **POST_NOTIFICATIONS** (Android 13+) — for the foreground-service
  notification.

Declared for the foreground service: `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`. Network: `INTERNET`, `ACCESS_NETWORK_STATE`.

---

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

Open the installed **AlphaPOS Operator Link** dev build on the phone; it
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

### Alternative: local build (no EAS cloud)

Requires Android Studio + SDK and a connected device/emulator. This runs
`expo prebuild` (which executes the config plugin) and compiles locally:

```bash
npm install
npx expo run:android
```

If you ever need to inspect or reset the generated native project:

```bash
npx expo prebuild --platform android --clean
```

The `android/` directory is generated and git-ignored — edit `app.json` and the
config plugin, not the native files.

---

## Testing the protocol without the real POS

A minimal Node WebSocket server that prints what the app sends (`npm i ws`
first, then `node test-server.js`):

```js
// test-server.js
const { WebSocketServer } = require('ws');
const PORT = 8765;
const wss = new WebSocketServer({ port: PORT });

wss.on('connection', (socket, req) => {
  console.log('operator connected:', req.url); // includes ?token=...
  socket.on('message', (data) => console.log('event:', data.toString()));
  socket.on('close', () => console.log('operator disconnected'));
});

console.log(`Mock POS listening on ws://0.0.0.0:${PORT}`);
```

Find your desktop's LAN IP (`ipconfig` on Windows, `ifconfig`/`ip addr`
elsewhere) and encode a QR for `ws://<that-ip>:8765?token=test123` (any QR
generator works). Scan it from the app and place/receive a call.

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
  "AlphaPOS Operator Link" notification is present (that is the foreground
  service). On aggressive OEM ROMs (Xiaomi, Huawei, Samsung), disable battery
  optimization / enable "autostart" for the app.
- **Nothing happens in Expo Go.** Expected — call detection and the foreground
  service are native. Use the dev build.
- **`new WebSocket` rejected on a release build.** Ensure
  `usesCleartextTraffic: true` (already set) survived prebuild, or pair to a
  `wss://` endpoint.
```
