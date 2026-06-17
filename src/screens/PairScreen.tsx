import { useState } from 'react';
import {
  ActivityIndicator,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';

/**
 * Full-screen QR scanner used to pair with the desktop POS.
 *
 * The QR encodes `ws://<desktop-lan-ip>:8765?token=<token>`. On a valid scan
 * the raw value is handed to `onPaired` verbatim (token included).
 */
export interface PairScreenProps {
  onPaired: (url: string) => void;
}

export function PairScreen({ onPaired }: PairScreenProps) {
  const [permission, requestPermission] = useCameraPermissions();
  const [error, setError] = useState<string | null>(null);
  // Guard so we act on the QR exactly once and stop the rapid-fire callback.
  const [handled, setHandled] = useState(false);

  // Permission state still loading.
  if (!permission) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color="#e2e8f0" />
      </View>
    );
  }

  // Camera permission not yet granted — explain why and offer the prompt.
  if (!permission.granted) {
    return (
      <View style={styles.centered}>
        <Text style={styles.title}>Camera access needed</Text>
        <Text style={styles.body}>
          AlphaPOS Operator Link scans the pairing QR shown on the POS desktop to
          connect over your local network.
        </Text>
        <TouchableOpacity style={styles.button} onPress={requestPermission}>
          <Text style={styles.buttonText}>Grant camera access</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const onBarcodeScanned = ({ data }: { data: string }) => {
    if (handled) return;
    const value = (data ?? '').trim();
    if (!value.toLowerCase().startsWith('ws://')) {
      // Invalid code: surface the reason and keep scanning.
      setError('That is not a valid pairing code. Expecting a ws:// address.');
      return;
    }
    setError(null);
    setHandled(true);
    onPaired(value);
  };

  return (
    <View style={styles.fill}>
      <CameraView
        style={styles.fill}
        facing="back"
        barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
        onBarcodeScanned={handled ? undefined : onBarcodeScanned}
      />
      <View style={styles.overlay} pointerEvents="none">
        <Text style={styles.heading}>Pair with POS</Text>
        <View style={styles.frame} />
        <Text style={styles.hint}>Point the camera at the pairing QR on the desktop.</Text>
        {error ? <Text style={styles.error}>{error}</Text> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: {
    flex: 1,
    backgroundColor: '#000',
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 28,
    backgroundColor: '#0b1120',
  },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
  },
  heading: {
    position: 'absolute',
    top: 72,
    color: '#f8fafc',
    fontSize: 22,
    fontWeight: '700',
  },
  frame: {
    width: 240,
    height: 240,
    borderWidth: 3,
    borderColor: '#38bdf8',
    borderRadius: 20,
    backgroundColor: 'transparent',
  },
  hint: {
    position: 'absolute',
    bottom: 120,
    color: '#e2e8f0',
    fontSize: 15,
    textAlign: 'center',
    paddingHorizontal: 32,
  },
  error: {
    position: 'absolute',
    bottom: 76,
    color: '#fca5a5',
    fontSize: 14,
    textAlign: 'center',
    paddingHorizontal: 32,
  },
  title: {
    color: '#f8fafc',
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 12,
    textAlign: 'center',
  },
  body: {
    color: '#94a3b8',
    fontSize: 15,
    lineHeight: 22,
    textAlign: 'center',
    marginBottom: 28,
  },
  button: {
    backgroundColor: '#2563eb',
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 12,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
