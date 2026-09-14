import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, AppState, StyleSheet, Text, View } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';

import { ScanLine } from '../components/anim';
import { CameraOff } from '../components/Icons';
import { Button, Screen } from '../components/ui';
import { openAppSettings } from '../permissions';
import { colors, fonts, radius, space } from '../theme';

export interface PairScreenProps {
  onPaired: (value: string) => Promise<void> | void;
  onClose: () => void;
  purpose?: 'pos' | 'telegram';
}

export function PairScreen({ onPaired, onClose, purpose = 'pos' }: PairScreenProps) {
  const [permission, requestPermission, getPermission] = useCameraPermissions();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const scanLock = useRef(false);

  useEffect(() => {
    const listener = AppState.addEventListener('change', (next) => { if (next === 'active') void getPermission().catch(() => {}); });
    return () => listener.remove();
  }, [getPermission]);

  const onBarcodeScanned = async ({ data }: { data: string }) => {
    if (scanLock.current) return;
    scanLock.current = true;
    const value = (data ?? '').trim();
    let supported = purpose === 'pos' && /^wss?:\/\//i.test(value);
    if (!supported) {
      try {
        const parsed = JSON.parse(value);
        supported = purpose === 'telegram' ? parsed?.type === 'smart_pos_telegram' && parsed?.version === 1 : parsed?.version === 2 && typeof parsed.id === 'string' && typeof parsed.name === 'string' && typeof parsed.url === 'string' && /^wss?:\/\//i.test(parsed.url);
      } catch { /* Invalid QR contents are handled below without displaying them. */ }
    }
    if (!supported) {
      setError(purpose === 'telegram' ? 'Bu Telegram sozlash QR kodi emas.' : 'Bu POS ulanish kodi emas. POS dagi Operator tugmasini bosib turing va ochilgan QR-kodni skanerlang.');
      return;
    }
    setSaving(true);
    setError(null);
    try { await onPaired(value); }
    catch { setError('Sozlamani saqlab bo‘lmadi. QR-kodni tekshirib, qayta urinib ko‘ring.'); }
    finally { setSaving(false); }
  };

  if (!permission || !permission.granted) {
    return (
      <Screen>
        <View style={styles.permissionBody}>
          {!permission ? <ActivityIndicator color={colors.brand} /> : <><CameraOff size={48} /><Text style={styles.title}>Kameraga ruxsat kerak</Text><Text style={styles.description}>Kamera ulanish QR-kodlarini skanerlash uchun ishlatiladi.</Text></>}
        </View>
        <View style={styles.footer}>
          {error ? <Text style={styles.error}>{error}</Text> : null}
          {permission ? <Button label={permission.canAskAgain ? 'Kameraga ruxsat berish' : 'Ilova sozlamalarini ochish'} onPress={() => { void (permission.canAskAgain ? requestPermission() : openAppSettings()).catch(() => setError('Kamera ruxsatini telefon sozlamalaridan bering.')); }} /> : null}
          <Button label="Orqaga" variant="secondary" onPress={onClose} />
        </View>
      </Screen>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: colors.bgDeep }}>
      <CameraView style={StyleSheet.absoluteFill} facing="back" barcodeScannerSettings={{ barcodeTypes: ['qr'] }} onBarcodeScanned={saving || error ? undefined : onBarcodeScanned} />
      <View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: 'rgba(10,13,19,0.48)' }]} />
      <Screen style={{ backgroundColor: 'transparent' }}>
        <View style={styles.heading}><Text accessibilityRole="header" style={styles.title}>{purpose === 'telegram' ? 'Telegramni sozlash' : 'POS qo‘shish'}</Text><Text style={styles.description}>{purpose === 'telegram' ? 'Siz uchun tayyorlangan maxsus Telegram QR-kodini skanerlang.' : 'POS dagi Operator tugmasini bosib turing. QR-kodni faqat bir marta skanerlash kifoya.'}</Text></View>
        <View style={styles.cameraBody}>
          <View accessibilityLabel="QR-kodni shu ramka ichiga joylashtiring" style={[styles.reticle, { borderColor: error ? colors.danger : colors.brand }]}>
            {!saving && !error ? <ScanLine color={colors.brand} travel={218} /> : null}
            {saving ? <ActivityIndicator size="large" color={colors.brand} /> : null}
          </View>
          <Text style={styles.scanHint}>{saving ? 'Sozlamalar saqlanmoqda…' : 'QR-kodni ramka ichiga joylashtiring'}</Text>
        </View>
        <View style={styles.footer}>
          {error ? <><Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text><Button label="Qayta skanerlash" onPress={() => { scanLock.current = false; setError(null); }} /></> : null}
          <Button label="Orqaga" variant="secondary" onPress={onClose} disabled={saving} />
        </View>
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  heading: { padding: space.xl, gap: 8 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 25, textAlign: 'center' },
  description: { color: colors.textSoft, fontFamily: fonts.regular, fontSize: 14, lineHeight: 21, textAlign: 'center' },
  permissionBody: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 28, gap: 16 },
  cameraBody: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 24 },
  reticle: { width: 240, height: 240, borderWidth: 3, borderRadius: 22, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  scanHint: { color: colors.text, fontFamily: fonts.medium, fontSize: 13, backgroundColor: 'rgba(10,13,19,0.75)', padding: 12, borderRadius: radius.pill },
  error: { color: colors.danger, fontFamily: fonts.medium, fontSize: 13, lineHeight: 20, padding: 16, borderRadius: radius.md, backgroundColor: colors.bgDeep },
  footer: { padding: space.lg, paddingBottom: space.xl, gap: 10 },
});
