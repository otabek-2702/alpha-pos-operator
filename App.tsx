import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, BackHandler, StatusBar, Text, View } from 'react-native';
import {
  HankenGrotesk_400Regular, HankenGrotesk_500Medium,
  HankenGrotesk_600SemiBold, HankenGrotesk_700Bold,
} from '@expo-google-fonts/hanken-grotesk';
import {
  JetBrainsMono_400Regular, JetBrainsMono_500Medium,
  JetBrainsMono_600SemiBold, JetBrainsMono_700Bold, useFonts,
} from '@expo-google-fonts/jetbrains-mono';
import { useAppUpdates } from './src/hooks/useAppUpdates';
import { I18nProvider } from './src/i18n';
import { PairScreen } from './src/screens/PairScreen';
import { PermissionsScreen } from './src/screens/PermissionsScreen';
import { SplashScreen } from './src/screens/SplashScreen';
import { StatusScreen } from './src/screens/StatusScreen';
import { SupportScreen } from './src/screens/SupportScreen';
import { TelegramScreen } from './src/screens/TelegramScreen';
import { WorkHistoryScreen } from './src/screens/WorkHistoryScreen';
import { checkPermissions, hasRequiredPermissions, PermissionState } from './src/permissions';
import { clearDesktopUrl, loadDesktopUrl } from './src/storage';
import {
  configureOperator, EMPTY_TELEGRAM, getOperatorConfiguration, getRuntimeSnapshot,
  OperatorConfiguration, parsePairingCode, parseTelegramSetupCode, pickRecordingFolder, RuntimeSnapshot,
  sendOperatorTest, TelegramSettings, upsertPos,
} from './src/operator';
import { colors, fonts } from './src/theme';

type Page = 'home' | 'pair' | 'telegram-scan' | 'permissions' | 'telegram' | 'history' | 'support';

export default function App() {
  return <I18nProvider><Root /></I18nProvider>;
}

function Root() {
  const [fontsLoaded, fontError] = useFonts({
    HankenGrotesk_400Regular, HankenGrotesk_500Medium,
    HankenGrotesk_600SemiBold, HankenGrotesk_700Bold,
    JetBrainsMono_400Regular, JetBrainsMono_500Medium,
    JetBrainsMono_600SemiBold, JetBrainsMono_700Bold,
  });
  const [ready, setReady] = useState(false);
  const [page, setPage] = useState<Page>('home');
  const [perms, setPerms] = useState<PermissionState | null>(null);
  const [config, setConfig] = useState<OperatorConfiguration>({ targets: [], telegram: EMPTY_TELEGRAM });
  const configRef = useRef(config);
  const [snapshot, setSnapshot] = useState<RuntimeSnapshot | null>(null);
  const [runtimeError, setRuntimeError] = useState('');
  const [testing, setTesting] = useState(false);
  const mutation = useRef<Promise<void>>(Promise.resolve());
  const updates = useAppUpdates();

  const refresh = useCallback(async () => {
    try {
      setSnapshot(await getRuntimeSnapshot());
      setRuntimeError('');
    } catch {
      setRuntimeError('Xizmat bilan aloqa yo‘q. Yangi APK o‘rnatilganini va ruxsatlarni tekshiring.');
    }
  }, []);

  useEffect(() => {
    let disposed = false;
    (async () => {
      const currentPermissions = await checkPermissions();
      if (disposed) return;
      setPerms(currentPermissions);
      if (!hasRequiredPermissions(currentPermissions)) setPage('permissions');
      try {
        let saved = await getOperatorConfiguration();
        const legacy = await loadDesktopUrl();
        if (legacy && saved.targets.length === 0) {
          try {
            const migrated = { ...saved, targets: [parsePairingCode(legacy)] };
            await configureOperator(migrated);
            saved = migrated;
            await clearDesktopUrl();
          } catch {
            // Keep the legacy key until migration has actually been saved.
          }
        } else if (legacy && saved.targets.length > 0) {
          await clearDesktopUrl();
        }
        if (disposed) return;
        configRef.current = saved;
        setConfig(saved);
      } catch {
        if (!disposed) setRuntimeError('Operator xizmatini yuklab bo‘lmadi. Yangi Android APKni o‘rnating.');
      } finally {
        if (!disposed) setReady(true);
      }
    })();
    return () => { disposed = true; };
  }, []);

  const corePermissions = !!(perms?.phone && perms?.callLog);
  useEffect(() => {
    if (!ready || !corePermissions) return;
    configureOperator(configRef.current)
      .then(refresh)
      .catch(() => setRuntimeError('Xizmat ishga tushmadi. Ruxsatlarni tekshirib, ilovani qayta oching.'));
  }, [ready, corePermissions, refresh]);

  useEffect(() => {
    if (!ready) return;
    void refresh();
    const timer = setInterval(() => {
      if (AppState.currentState === 'active') void refresh();
    }, 2000);
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active') {
        void checkPermissions().then(setPerms);
        void refresh();
      }
    });
    return () => { clearInterval(timer); subscription.remove(); };
  }, [ready, refresh]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (page === 'home') return false;
      setPage('home');
      return true;
    });
    return () => subscription.remove();
  }, [page]);

  const save = (change: (previous: OperatorConfiguration) => OperatorConfiguration): Promise<void> => {
    const operation = mutation.current.catch(() => {}).then(async () => {
      await configureOperator(change(configRef.current));
      const saved = await getOperatorConfiguration();
      configRef.current = saved;
      setConfig(saved);
      await refresh();
    });
    mutation.current = operation;
    return operation;
  };

  const handlePaired = async (raw: string) => {
    const target = parsePairingCode(raw);
    await save((previous) => ({ ...previous, targets: upsertPos(previous.targets, target) }));
    setPage('home');
  };

  const handleRemove = (id: string) => {
    const target = configRef.current.targets.find((item) => item.id === id);
    if (!target) return;
    Alert.alert('POSni o‘chirish', `${target.name} uchun qo‘ng‘iroq yuborish to‘xtatiladi.`, [
      { text: 'Bekor qilish', style: 'cancel' },
      { text: 'O‘chirish', style: 'destructive', onPress: () => {
        void save((previous) => ({ ...previous, targets: previous.targets.filter((item) => item.id !== id) }))
          .catch(() => Alert.alert('Saqlanmadi', 'POSni o‘chirib bo‘lmadi. Qayta urinib ko‘ring.'));
      } },
    ]);
  };

  const handleTest = async () => {
    if (testing) return;
    setTesting(true);
    try {
      const count = await sendOperatorTest();
      Alert.alert(count > 0 ? 'Sinov yuborildi' : 'Ulangan POS yo‘q',
        count > 0 ? `${count} ta POSga sinov yuborildi. POS ekranini tekshiring.` : 'POSdagi operator rejimi va Wi-Fi ulanishini tekshiring.');
    } catch {
      Alert.alert('Sinov yuborilmadi', 'Xizmat va POS ulanishlarini tekshiring.');
    } finally { setTesting(false); await refresh(); }
  };

  const handleTelegramSave = async (telegram: TelegramSettings) => {
    await save((previous) => ({ ...previous, telegram }));
  };

  const handleTelegramScan = async (raw: string) => {
    const setup = parseTelegramSetupCode(raw);
    await save((previous) => ({ ...previous, telegram: { ...previous.telegram, ...setup, sendCallStats: true } }));
    setPage('telegram');
  };

  let content: React.ReactNode;
  if ((!fontsLoaded && !fontError) || !ready) {
    content = <SplashScreen />;
  } else if (page === 'pair') {
    content = <PairScreen onPaired={handlePaired} onClose={() => setPage('home')} />;
  } else if (page === 'telegram-scan') {
    content = <PairScreen purpose="telegram" onPaired={handleTelegramScan} onClose={() => setPage('telegram')} />;
  } else if (page === 'permissions') {
    content = <PermissionsScreen onClose={() => setPage('home')} onReady={async () => {
      setPerms(await checkPermissions());
      setPage('home');
    }} />;
  } else if (page === 'telegram') {
    content = <TelegramScreen config={config.telegram} snapshot={snapshot?.telegram ?? null}
      onSave={handleTelegramSave} onPickFolder={pickRecordingFolder} onScanSetup={() => setPage('telegram-scan')} onClose={() => setPage('home')} />;
  } else if (page === 'history') {
    content = <WorkHistoryScreen periods={snapshot?.periods ?? []} onClose={() => setPage('home')} />;
  } else if (page === 'support') {
    content = <SupportScreen updates={updates} onClose={() => setPage('home')} />;
  } else {
    content = <StatusScreen snapshot={snapshot} targets={config.targets} permissionGranted={corePermissions}
      updates={updates} onAdd={() => setPage('pair')} onRemove={handleRemove} onSendTest={handleTest}
      onOpenPermissions={() => setPage('permissions')} onOpenTelegram={() => setPage('telegram')}
      onOpenHistory={() => setPage('history')} onOpenSupport={() => setPage('support')} testing={testing} />;
  }

  return <View style={{ flex: 1, backgroundColor: colors.bg }}>
    <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
    {content}
    {runtimeError && ready && page === 'home' ? <Text accessibilityRole="alert" style={{
      color: colors.danger, padding: 12, fontFamily: fonts.medium, fontSize: 13,
    }}>{runtimeError}</Text> : null}
  </View>;
}
