import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, AppState, BackHandler, ScrollView, StatusBar, Text, View } from 'react-native';
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
import { Button, Screen } from './src/components/ui';
import { PairScreen } from './src/screens/PairScreen';
import { PermissionsScreen } from './src/screens/PermissionsScreen';
import { SplashScreen } from './src/screens/SplashScreen';
import { StatusScreen } from './src/screens/StatusScreen';
import { SupportScreen } from './src/screens/SupportScreen';
import { TelegramScreen } from './src/screens/TelegramScreen';
import { WorkHistoryScreen } from './src/screens/WorkHistoryScreen';
import { ManagersScreen } from './src/screens/ManagersScreen';
import { WorkScheduleScreen } from './src/screens/WorkScheduleScreen';
import { checkPermissions, hasRequiredPermissions, PermissionState } from './src/permissions';
import { clearDesktopUrl, loadDesktopUrl } from './src/storage';
import {
  configureOperator, EMPTY_TELEGRAM, getOperatorConfiguration, getRuntimeSnapshot,
  OperatorConfiguration, parsePairingCode, parseTelegramSetupCode, pickRecordingFolder, RuntimeSnapshot,
  sendOperatorTest, TelegramSettings, upsertPos, DEFAULT_ALERTS, DEFAULT_CLOSED_SMS, DEFAULT_SHIFTS,
} from './src/operator';
import type { AlertConfig, ClosedSmsConfig, ManagerConfig, PosRole, SavedPos, ShiftConfig } from './src/operator';
import { colors, fonts } from './src/theme';

type Page = 'home' | 'pair' | 'telegram-scan' | 'permissions' | 'telegram' | 'history' | 'support' | 'schedule' | 'managers';
const CONFIG_LOAD_ERROR = 'Saqlangan POS va Telegram sozlamalarini o‘qib bo‘lmadi. “Qayta yuklash” tugmasini bosing. Muammo davom etsa, ilovani qayta oching yoki yangi APKni o‘rnating.';

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
  const [configLoaded, setConfigLoaded] = useState(false);
  const configLoadedRef = useRef(false);
  const [configLoadError, setConfigLoadError] = useState('');
  const [loadingConfig, setLoadingConfig] = useState(false);
  const loadingConfigRef = useRef(false);
  const mounted = useRef(true);
  const [snapshot, setSnapshot] = useState<RuntimeSnapshot | null>(null);
  const [runtimeError, setRuntimeError] = useState('');
  const [testing, setTesting] = useState(false);
  const mutation = useRef<Promise<void>>(Promise.resolve());
  const updates = useAppUpdates(snapshot?.update ?? null);

  const configRevision = useRef<number | null>(null);
  const refresh = useCallback(async () => {
    try {
      const next = await getRuntimeSnapshot();
      setSnapshot(next);
      setRuntimeError('');
      // Settings changed elsewhere (owner bot commands): show the stored version.
      const revision = typeof next.configRevision === 'number' ? next.configRevision : null;
      if (revision !== null && configRevision.current !== null && revision !== configRevision.current && configLoadedRef.current && !loadingConfigRef.current) {
        configRevision.current = revision;
        const operation = mutation.current.catch(() => {}).then(async () => {
          const stored = await getOperatorConfiguration();
          if (!mounted.current) return;
          configRef.current = stored;
          setConfig(stored);
        });
        mutation.current = operation;
        void operation.catch(() => {});
      } else if (revision !== null) {
        configRevision.current = revision;
      }
    } catch {
      setRuntimeError('Xizmat bilan aloqa yo‘q. Yangi APK o‘rnatilganini va ruxsatlarni tekshiring.');
    }
  }, []);

  const markConfigUnavailable = useCallback(() => {
    configLoadedRef.current = false;
    if (mounted.current) {
      setConfigLoaded(false);
      setConfigLoadError(CONFIG_LOAD_ERROR);
    }
  }, []);

  const loadConfiguration = useCallback(async () => {
    if (loadingConfigRef.current) return;
    loadingConfigRef.current = true;
    configLoadedRef.current = false;
    setLoadingConfig(true);
    setConfigLoaded(false);
    // A retry after a failed readback must wait for the existing save queue.
    // Pending saves check configLoadedRef again before making any native write.
    try {
      await mutation.current.catch(() => {});
      if (!mounted.current) return;
      let saved = await getOperatorConfiguration();
      if (!mounted.current) return;
      const legacy = await loadDesktopUrl();
      if (!mounted.current) return;
      if (legacy && saved.targets.length === 0) {
        try {
          const migrated = { ...saved, targets: [parsePairingCode(legacy)] };
          await configureOperator(migrated);
          saved = migrated;
          await clearDesktopUrl();
        } catch {
          // Starting the service can fail after the migrated config was saved.
          // Read it back before allowing a later startup write; keep the legacy key.
          saved = await getOperatorConfiguration();
        }
      } else if (legacy && saved.targets.length > 0) {
        await clearDesktopUrl();
      }
      if (!mounted.current) return;
      configRef.current = saved;
      configLoadedRef.current = true;
      setConfig(saved);
      setConfigLoaded(true);
      setConfigLoadError('');
    } catch {
      markConfigUnavailable();
    } finally {
      loadingConfigRef.current = false;
      if (mounted.current) setLoadingConfig(false);
    }
  }, [markConfigUnavailable]);

  useEffect(() => {
    mounted.current = true;
    const permissions = checkPermissions().then((currentPermissions) => {
      if (!mounted.current) return;
      setPerms(currentPermissions);
      if (!hasRequiredPermissions(currentPermissions)) setPage('permissions');
    }).catch(() => {
      if (mounted.current) setRuntimeError('Ruxsatlarni tekshirib bo‘lmadi. Ilovani qayta oching.');
    });
    void Promise.all([permissions, loadConfiguration()]).finally(() => {
      if (mounted.current) setReady(true);
    });
    return () => { mounted.current = false; };
  }, [loadConfiguration]);

  const corePermissions = !!(perms?.phone && perms?.callLog);
  useEffect(() => {
    if (!ready || !corePermissions || !configLoaded) return;
    let cancelled = false;
    const operation = mutation.current.catch(() => {}).then(async () => {
      if (cancelled || !configLoadedRef.current || loadingConfigRef.current) return;
      await configureOperator(await getOperatorConfiguration());
      await refresh();
    });
    mutation.current = operation;
    void operation
      .catch(() => setRuntimeError('Xizmat ishga tushmadi. Ruxsatlarni tekshirib, ilovani qayta oching.'));
    return () => { cancelled = true; };
  }, [ready, corePermissions, configLoaded, refresh]);

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
    if (!configLoadedRef.current || loadingConfigRef.current) return Promise.reject(new Error(CONFIG_LOAD_ERROR));
    const operation = mutation.current.catch(() => {}).then(async () => {
      if (!configLoadedRef.current || loadingConfigRef.current) throw new Error(CONFIG_LOAD_ERROR);
      let saved: OperatorConfiguration;
      try {
        const current = await getOperatorConfiguration();
        await configureOperator(change(current));
        saved = await getOperatorConfiguration();
      } catch (error) {
        // The write may have succeeded; block later writes based on a stale view.
        markConfigUnavailable();
        throw error;
      }
      configRef.current = saved;
      setConfig(saved);
      await refresh();
    });
    mutation.current = operation;
    return operation;
  };

  /** New POS: ask whether it takes operator calls (popup) or is a cashier (number only). */
  const askRole = (name: string) => new Promise<PosRole>((resolve) => {
    Alert.alert('Bu POS qanday ishlatiladi?', `${name}\n\nOperator: qo‘ng‘iroq kelganda oyna ochiladi.\nKassa: oyna ochilmaydi, qo‘ng‘iroqdagi raqam tez kiritish uchun beriladi.`, [
      { text: 'Kassa', onPress: () => resolve('cashier') },
      { text: 'Operator', onPress: () => resolve('operator') },
    ], { cancelable: false });
  });

  const handlePaired = async (raw: string) => {
    const scanned = parsePairingCode(raw);
    const existing = configRef.current.targets.find((item) => item.id === scanned.id);
    const target: SavedPos = { ...scanned, role: existing?.role ?? await askRole(scanned.name) };
    await save((previous) => ({ ...previous, targets: upsertPos(previous.targets, target) }));
    setPage('home');
  };

  const handleRoleChange = (id: string, role: PosRole) => {
    void save((previous) => ({ ...previous, targets: previous.targets.map((item) => (item.id === id ? { ...item, role } : item)) }))
      .catch(() => Alert.alert('Saqlanmadi', 'POS turini o‘zgartirib bo‘lmadi. Qayta urinib ko‘ring.'));
  };

  const handleScheduleSave = async (value: { shifts: ShiftConfig[]; closedSms: ClosedSmsConfig; alerts: AlertConfig }) => {
    await save((previous) => ({ ...previous, ...value }));
  };

  const handleManagersSave = async (managers: ManagerConfig[]) => {
    await save((previous) => ({ ...previous, managers }));
  };

  const handleAdminRenew = async () => {
    await save((previous) => ({ ...previous, adminInvite: 'new' }));
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
  } else if (!configLoaded) {
    content = <Screen>
      <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: 24, gap: 20 }}>
        <Text accessibilityRole="header" style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 26 }}>Sozlamalar yuklanmadi</Text>
        <Text accessibilityRole="alert" accessibilityLiveRegion="polite" style={{ color: colors.textSoft, fontFamily: fonts.medium, fontSize: 16, lineHeight: 24 }}>
          {configLoadError || CONFIG_LOAD_ERROR}
        </Text>
        <Button label="Qayta yuklash" onPress={() => void loadConfiguration()} loading={loadingConfig} disabled={loadingConfig} />
      </ScrollView>
    </Screen>;
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
  } else if (page === 'schedule') {
    content = <WorkScheduleScreen shifts={config.shifts ?? DEFAULT_SHIFTS} closedSms={config.closedSms ?? { enabled: false, text: DEFAULT_CLOSED_SMS }}
      alerts={config.alerts ?? DEFAULT_ALERTS} operations={snapshot?.operations ?? null} onSave={handleScheduleSave}
      onOpenPermissions={() => setPage('permissions')} onClose={() => setPage('home')} />;
  } else if (page === 'managers') {
    content = <ManagersScreen managers={config.managers ?? []} shifts={config.shifts ?? DEFAULT_SHIFTS} operations={snapshot?.operations ?? null}
      onSave={handleManagersSave} adminInvite={config.adminInvite} onRenewAdmin={handleAdminRenew} onClose={() => setPage('home')} />;
  } else if (page === 'history') {
    content = <WorkHistoryScreen periods={snapshot?.periods ?? []} onClose={() => setPage('home')} />;
  } else if (page === 'support') {
    content = <SupportScreen updates={updates} onClose={() => setPage('home')} />;
  } else {
    content = <StatusScreen snapshot={snapshot} targets={config.targets} permissionGranted={corePermissions}
      updates={updates} onAdd={() => setPage('pair')} onRemove={handleRemove} onSendTest={handleTest} onRoleChange={handleRoleChange}
      onOpenSchedule={() => setPage('schedule')} onOpenManagers={() => setPage('managers')} managerCount={config.managers?.length ?? 0}
      onOpenPermissions={() => setPage('permissions')} onOpenTelegram={() => setPage('telegram')}
      onOpenHistory={() => setPage('history')} onOpenSupport={() => setPage('support')} testing={testing} />;
  }

  return <View style={{ flex: 1, backgroundColor: colors.bg }}>
    <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />
    {content}
    {runtimeError && ready && configLoaded && page === 'home' ? <Text accessibilityRole="alert" style={{
      color: colors.danger, padding: 12, fontFamily: fonts.medium, fontSize: 13,
    }}>{runtimeError}</Text> : null}
  </View>;
}
