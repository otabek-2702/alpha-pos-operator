import {
  FlatList,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import type { CallLogEntry } from '../hooks/useCallBridge';
import type { WsStatus } from '../hooks/useWebSocket';

/**
 * Shown once paired. Displays live connection status, the paired desktop
 * address, a permission warning if call access was denied, the last ~10 call
 * events sent, and a Re-pair action that clears the saved URL.
 */
export interface StatusScreenProps {
  url: string;
  status: WsStatus;
  log: CallLogEntry[];
  permissionGranted: boolean | null;
  onRepair: () => void;
}

const STATUS_META: Record<WsStatus, { label: string; color: string }> = {
  connected: { label: 'Connected', color: '#22c55e' },
  connecting: { label: 'Connecting…', color: '#eab308' },
  reconnecting: { label: 'Reconnecting…', color: '#eab308' },
  disconnected: { label: 'Disconnected', color: '#ef4444' },
};

function formatTime(at: number): string {
  const d = new Date(at);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function describeEntry(entry: CallLogEntry): string {
  if (entry.type === 'call_start') {
    const dir = entry.direction === 'out' ? 'Outgoing' : 'Incoming';
    return `${dir} call started`;
  }
  return 'Call ended';
}

export function StatusScreen({
  url,
  status,
  log,
  permissionGranted,
  onRepair,
}: StatusScreenProps) {
  const meta = STATUS_META[status];

  return (
    <View style={styles.container}>
      <Text style={styles.appName}>AlphaPOS Operator Link</Text>

      <View style={styles.card}>
        <View style={styles.statusRow}>
          <View style={[styles.dot, { backgroundColor: meta.color }]} />
          <Text style={[styles.statusText, { color: meta.color }]}>{meta.label}</Text>
        </View>
        <Text style={styles.label}>Paired desktop</Text>
        <Text style={styles.address} numberOfLines={2}>
          {url}
        </Text>
      </View>

      {permissionGranted === false ? (
        <View style={styles.warning}>
          <Text style={styles.warningText}>
            Phone / call-log permission was denied. Caller numbers cannot be read.
            Enable both permissions in system settings, then reopen the app.
          </Text>
        </View>
      ) : null}

      <Text style={styles.sectionTitle}>Recent call events</Text>
      <View style={styles.logCard}>
        {log.length === 0 ? (
          <Text style={styles.empty}>
            No calls yet. Incoming and outgoing calls will appear here as they are
            sent to the POS.
          </Text>
        ) : (
          <FlatList
            data={log}
            keyExtractor={(item) => item.id}
            renderItem={({ item }) => (
              <View style={styles.logRow}>
                <View style={styles.logMain}>
                  <Text style={styles.logEvent}>{describeEntry(item)}</Text>
                  <Text style={styles.logPhone}>{item.phone || '(no number)'}</Text>
                </View>
                <Text style={styles.logTime}>{formatTime(item.at)}</Text>
              </View>
            )}
            ItemSeparatorComponent={() => <View style={styles.separator} />}
          />
        )}
      </View>

      <TouchableOpacity style={styles.repairButton} onPress={onRepair}>
        <Text style={styles.repairText}>Re-pair (scan a new QR)</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0b1120',
    paddingHorizontal: 20,
    paddingTop: 64,
    paddingBottom: 24,
  },
  appName: {
    color: '#f8fafc',
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 20,
  },
  card: {
    backgroundColor: '#111c33',
    borderRadius: 16,
    padding: 18,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 14,
  },
  dot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 10,
  },
  statusText: {
    fontSize: 18,
    fontWeight: '700',
  },
  label: {
    color: '#64748b',
    fontSize: 12,
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginBottom: 4,
  },
  address: {
    color: '#e2e8f0',
    fontSize: 14,
    fontFamily: 'monospace',
  },
  warning: {
    backgroundColor: '#3f1d1d',
    borderColor: '#7f1d1d',
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
    marginTop: 16,
  },
  warningText: {
    color: '#fecaca',
    fontSize: 13,
    lineHeight: 19,
  },
  sectionTitle: {
    color: '#94a3b8',
    fontSize: 13,
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginTop: 24,
    marginBottom: 10,
  },
  logCard: {
    flex: 1,
    backgroundColor: '#111c33',
    borderRadius: 16,
    padding: 14,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  empty: {
    color: '#64748b',
    fontSize: 14,
    lineHeight: 21,
  },
  logRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
  },
  logMain: {
    flex: 1,
    paddingRight: 12,
  },
  logEvent: {
    color: '#f1f5f9',
    fontSize: 15,
    fontWeight: '600',
  },
  logPhone: {
    color: '#94a3b8',
    fontSize: 13,
    fontFamily: 'monospace',
    marginTop: 2,
  },
  logTime: {
    color: '#64748b',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  separator: {
    height: 1,
    backgroundColor: '#1e293b',
  },
  repairButton: {
    marginTop: 18,
    backgroundColor: '#1e293b',
    borderRadius: 12,
    paddingVertical: 16,
    alignItems: 'center',
  },
  repairText: {
    color: '#f8fafc',
    fontSize: 16,
    fontWeight: '600',
  },
});
