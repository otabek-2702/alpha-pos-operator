import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  PermissionsAndroid,
  Platform,
  StatusBar,
  StyleSheet,
  View,
} from 'react-native';

import { useCallBridge } from './src/hooks/useCallBridge';
import { useWebSocket } from './src/hooks/useWebSocket';
import { PairScreen } from './src/screens/PairScreen';
import { StatusScreen } from './src/screens/StatusScreen';
import { clearDesktopUrl, loadDesktopUrl, saveDesktopUrl } from './src/storage';

/**
 * Root component.
 *
 * - On launch, loads the saved desktop URL and auto-reconnects if present.
 * - Renders the QR PairScreen when unpaired, the StatusScreen when paired.
 * - The call bridge runs for the app's lifetime so detection works while
 *   backgrounded (kept alive by the foreground service). Events only leave the
 *   device once paired and connected; otherwise they are buffered.
 */
export default function App() {
  const [url, setUrl] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  // Load any previously paired desktop URL.
  useEffect(() => {
    (async () => {
      setUrl(await loadDesktopUrl());
      setReady(true);
    })();
  }, []);

  // Android 13+ requires a runtime prompt for the foreground-service notification.
  useEffect(() => {
    if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
      PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS!
      ).catch(() => {
        // Non-fatal: the service still runs; the notification is just silent.
      });
    }
  }, []);

  const { status, send } = useWebSocket(url);
  const { log, permissionGranted } = useCallBridge(send);

  const handlePaired = async (next: string) => {
    await saveDesktopUrl(next);
    setUrl(next);
  };

  const handleRepair = async () => {
    await clearDesktopUrl();
    setUrl(null);
  };

  if (!ready) {
    return (
      <View style={styles.loading}>
        <StatusBar barStyle="light-content" backgroundColor="#0b1120" />
        <ActivityIndicator color="#e2e8f0" />
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0b1120" />
      {url ? (
        <StatusScreen
          url={url}
          status={status}
          log={log}
          permissionGranted={permissionGranted}
          onRepair={handleRepair}
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
