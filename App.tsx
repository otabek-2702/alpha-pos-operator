import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  StatusBar,
  StyleSheet,
  View,
} from 'react-native';

import { useCallBridge } from './src/hooks/useCallBridge';
import { useWebSocket } from './src/hooks/useWebSocket';
import { PairScreen } from './src/screens/PairScreen';
import { PermissionsScreen } from './src/screens/PermissionsScreen';
import { StatusScreen } from './src/screens/StatusScreen';
import {
  checkPermissions,
  hasRequiredPermissions,
  PermissionState,
} from './src/permissions';
import { clearDesktopUrl, loadDesktopUrl, saveDesktopUrl } from './src/storage';

/**
 * Root component.
 *
 * Screen order:
 *   1. PermissionsScreen — until camera + phone + call-log are granted.
 *   2. PairScreen        — QR scanner, when no desktop URL is saved.
 *   3. StatusScreen      — once paired.
 *
 * The call bridge runs for the app's lifetime (kept alive by the foreground
 * service) but only emits while paired and connected; otherwise events buffer.
 */
export default function App() {
  const [url, setUrl] = useState<string | null>(null);
  const [perms, setPerms] = useState<PermissionState | null>(null);
  const [gateDone, setGateDone] = useState(false);
  const [ready, setReady] = useState(false);

  // Initial load: saved URL + current permission grants.
  useEffect(() => {
    (async () => {
      const [savedUrl, current] = await Promise.all([
        loadDesktopUrl(),
        checkPermissions(),
      ]);
      setUrl(savedUrl);
      setPerms(current);
      // Returning users who already granted everything skip the gate.
      if (hasRequiredPermissions(current)) setGateDone(true);
      setReady(true);
    })();
  }, []);

  // Re-check permissions whenever the app returns to the foreground (e.g. after
  // the user toggled something in system settings).
  const appState = useRef(AppState.currentState);
  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      if (appState.current.match(/inactive|background/) && next === 'active') {
        checkPermissions().then(setPerms);
      }
      appState.current = next;
    });
    return () => sub.remove();
  }, []);

  const callEnabled = perms ? hasRequiredPermissions(perms) : false;

  const { status, send } = useWebSocket(url);
  const { log } = useCallBridge(send, callEnabled);

  const handlePaired = async (next: string) => {
    await saveDesktopUrl(next);
    setUrl(next);
  };

  const handleRepair = async () => {
    await clearDesktopUrl();
    setUrl(null);
  };

  // Sends a synthetic incoming call to verify the desktop pipe end-to-end.
  const handleSendTest = () => {
    const phone = '+10000000000';
    send({ type: 'call_start', phone, direction: 'in' });
    setTimeout(() => send({ type: 'call_end', phone }), 1500);
  };

  if (!ready) {
    return (
      <View style={styles.loading}>
        <StatusBar barStyle="light-content" backgroundColor="#0b1120" />
        <ActivityIndicator color="#e2e8f0" />
      </View>
    );
  }

  const showGate = !gateDone;

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0b1120" />
      {showGate ? (
        <PermissionsScreen
          onReady={async () => {
            setPerms(await checkPermissions());
            setGateDone(true);
          }}
        />
      ) : url ? (
        <StatusScreen
          url={url}
          status={status}
          log={log}
          permissionGranted={callEnabled}
          onRepair={handleRepair}
          onSendTest={handleSendTest}
        />
      ) : (
        <PairScreen onPaired={handlePaired} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#0b1120',
  },
  loading: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#0b1120',
  },
});
