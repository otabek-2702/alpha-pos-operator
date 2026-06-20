import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import {
  checkPermissions,
  hasRequiredPermissions,
  openAppSettings,
  PermissionState,
  requestAllPermissions,
  requestBatteryExemption,
  wasBatteryPrompted,
} from '../permissions';

/**
 * Permission onboarding gate. Shown until camera, phone-state and call-log are
 * granted. Requests everything in one sequence (so no dialog gets dropped),
 * exposes a battery-optimization exemption, and falls back to system settings
 * when a permission was denied permanently.
 */
export interface PermissionsScreenProps {
  onReady: () => void;
}

interface Row {
  key: keyof PermissionState;
  label: string;
  detail: string;
  required: boolean;
}

const ROWS: Row[] = [
  { key: 'camera', label: 'Camera', detail: 'Scan the POS pairing QR code.', required: true },
  { key: 'phone', label: 'Phone state', detail: 'Detect when calls start and end.', required: true },
  { key: 'callLog', label: 'Call log', detail: 'Read the caller number (required on Android 9+).', required: true },
  { key: 'notifications', label: 'Notifications', detail: 'Show the always-on background-service notice.', required: false },
];

export function PermissionsScreen({ onReady }: PermissionsScreenProps) {
  const [state, setState] = useState<PermissionState | null>(null);
  const [busy, setBusy] = useState(false);
  const [batteryDone, setBatteryDone] = useState(false);

  const refresh = useCallback(async () => {
    const next = await checkPermissions();
    setState(next);
    return next;
  }, []);

  useEffect(() => {
    (async () => {
      await refresh();
      setBatteryDone(await wasBatteryPrompted());
    })();
  }, [refresh]);

  const grant = useCallback(async () => {
    setBusy(true);
    try {
      const next = await requestAllPermissions();
      setState(next);
    } finally {
      setBusy(false);
    }
  }, []);

  const handleBattery = useCallback(async () => {
    await requestBatteryExemption();
    setBatteryDone(true);
  }, []);

  if (!state) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color="#e2e8f0" />
      </View>
    );
  }

  const ready = hasRequiredPermissions(state);
  // Something required is off after a request attempt → likely denied for good.
  const someDenied = ROWS.some((r) => r.required && !state[r.key]);

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Permissions</Text>
      <Text style={styles.subtitle}>
        AlphaPOS Operator Link needs these to read incoming calls and stay
        running on the operator phone.
      </Text>

      <View style={styles.card}>
        {ROWS.map((row) => {
          const granted = state[row.key];
          return (
            <View key={row.key} style={styles.row}>
              <View style={[styles.statusPill, granted ? styles.pillOn : styles.pillOff]}>
                <Text style={styles.statusPillText}>{granted ? '✓' : '✕'}</Text>
              </View>
              <View style={styles.rowText}>
                <Text style={styles.rowLabel}>
                  {row.label}
                  {row.required ? '' : '  (optional)'}
                </Text>
                <Text style={styles.rowDetail}>{row.detail}</Text>
              </View>
            </View>
          );
        })}
      </View>

      <TouchableOpacity
        style={[styles.button, styles.primary]}
        onPress={grant}
        disabled={busy}
      >
        {busy ? (
          <ActivityIndicator color="#fff" />
        ) : (
          <Text style={styles.buttonText}>
            {ready ? 'Re-check permissions' : 'Grant permissions'}
          </Text>
        )}
      </TouchableOpacity>

      <TouchableOpacity
        style={[styles.button, batteryDone ? styles.secondaryDone : styles.secondary]}
        onPress={handleBattery}
      >
        <Text style={styles.buttonText}>
          {batteryDone ? 'Battery: re-open exemption' : 'Disable battery optimization'}
        </Text>
      </TouchableOpacity>
      <Text style={styles.hint}>
        Without the battery exemption, Android may kill the app in the background
        and calls will stop reaching the POS.
      </Text>

      {someDenied ? (
        <TouchableOpacity style={[styles.button, styles.ghost]} onPress={openAppSettings}>
          <Text style={styles.ghostText}>
            A permission was denied — open system settings
          </Text>
        </TouchableOpacity>
      ) : null}

      <TouchableOpacity
        style={[styles.button, ready ? styles.primary : styles.disabled]}
        onPress={onReady}
        disabled={!ready}
      >
        <Text style={styles.buttonText}>
          {ready ? 'Continue' : 'Grant required permissions to continue'}
        </Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#0b1120',
  },
  container: {
    padding: 24,
    paddingTop: 64,
    backgroundColor: '#0b1120',
    flexGrow: 1,
  },
  title: {
    color: '#f8fafc',
    fontSize: 26,
    fontWeight: '700',
  },
  subtitle: {
    color: '#94a3b8',
    fontSize: 15,
    lineHeight: 22,
    marginTop: 8,
    marginBottom: 24,
  },
  card: {
    backgroundColor: '#111c33',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#1e293b',
    padding: 8,
    marginBottom: 20,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
  },
  statusPill: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 14,
  },
  pillOn: { backgroundColor: '#16a34a' },
  pillOff: { backgroundColor: '#7f1d1d' },
  statusPillText: { color: '#fff', fontSize: 15, fontWeight: '700' },
  rowText: { flex: 1 },
  rowLabel: { color: '#f1f5f9', fontSize: 16, fontWeight: '600' },
  rowDetail: { color: '#94a3b8', fontSize: 13, marginTop: 2, lineHeight: 18 },
  button: {
    borderRadius: 12,
    paddingVertical: 15,
    alignItems: 'center',
    marginTop: 12,
  },
  primary: { backgroundColor: '#2563eb' },
  secondary: { backgroundColor: '#7c3aed' },
  secondaryDone: { backgroundColor: '#334155' },
  ghost: { backgroundColor: 'transparent', borderWidth: 1, borderColor: '#475569' },
  disabled: { backgroundColor: '#1e293b' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  ghostText: { color: '#cbd5e1', fontSize: 14, fontWeight: '600' },
  hint: {
    color: '#64748b',
    fontSize: 12,
    lineHeight: 18,
    marginTop: 8,
    paddingHorizontal: 4,
  },
});
