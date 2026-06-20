import { useEffect, useRef, useState } from 'react';
import { AppState, StatusBar } from 'react-native';
import {
  HankenGrotesk_400Regular,
  HankenGrotesk_500Medium,
  HankenGrotesk_600SemiBold,
  HankenGrotesk_700Bold,
} from '@expo-google-fonts/hanken-grotesk';
import {
  JetBrainsMono_400Regular,
  JetBrainsMono_500Medium,
  JetBrainsMono_600SemiBold,
  JetBrainsMono_700Bold,
  useFonts,
} from '@expo-google-fonts/jetbrains-mono';

import { useCallBridge } from './src/hooks/useCallBridge';
import { useWebSocket } from './src/hooks/useWebSocket';
import { I18nProvider } from './src/i18n';
import { PairScreen } from './src/screens/PairScreen';
import { PermissionsScreen } from './src/screens/PermissionsScreen';
import { SplashScreen } from './src/screens/SplashScreen';
import { StatusScreen } from './src/screens/StatusScreen';
import {
  checkPermissions,
  hasRequiredPermissions,
  openAppSettings,
  PermissionState,
} from './src/permissions';
import { clearDesktopUrl, loadDesktopUrl, saveDesktopUrl } from './src/storage';

export default function App() {
  return (
    <I18nProvider>
      <Root />
    </I18nProvider>
  );
}

function Root() {
  const [fontsLoaded] = useFonts({
    HankenGrotesk_400Regular,
    HankenGrotesk_500Medium,
    HankenGrotesk_600SemiBold,
    HankenGrotesk_700Bold,
    JetBrainsMono_400Regular,
    JetBrainsMono_500Medium,
    JetBrainsMono_600SemiBold,
    JetBrainsMono_700Bold,
  });

  const [url, setUrl] = useState<string | null>(null);
  const [perms, setPerms] = useState<PermissionState | null>(null);
  const [gateDone, setGateDone] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    (async () => {
      const [savedUrl, current] = await Promise.all([loadDesktopUrl(), checkPermissions()]);
      setUrl(savedUrl);
      setPerms(current);
      if (hasRequiredPermissions(current)) setGateDone(true);
      setReady(true);
    })();
  }, []);

  // Re-check permissions when returning to the foreground (covers settings + revoke).
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

  const { status, queuedCount, send } = useWebSocket(url);
  const { log, activeCall } = useCallBridge(send, callEnabled);

  const handlePaired = async (next: string) => {
    await saveDesktopUrl(next);
    setUrl(next);
  };
  const handleRepair = async () => {
    await clearDesktopUrl();
    setUrl(null);
  };
  const handleSendTest = () => {
    const phone = '+10000000000';
    send({ type: 'call_start', phone, direction: 'in' });
    setTimeout(() => send({ type: 'call_end', phone }), 1500);
  };

  const showSplash = !fontsLoaded || !ready;

  let content: React.ReactNode;
  if (showSplash) {
    content = <SplashScreen />;
  } else if (!gateDone) {
    content = (
      <PermissionsScreen
        onReady={async () => {
          setPerms(await checkPermissions());
          setGateDone(true);
        }}
      />
    );
  } else if (url) {
    content = (
      <StatusScreen
        url={url}
        status={status}
        log={log}
        permissionGranted={callEnabled}
        queuedCount={queuedCount}
        activeCall={activeCall}
        onRepair={handleRepair}
        onSendTest={handleSendTest}
        onFixPermission={openAppSettings}
      />
    );
  } else {
    content = <PairScreen onPaired={handlePaired} />;
  }

  return (
    <>
      <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
      {content}
    </>
  );
}
