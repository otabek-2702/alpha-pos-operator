import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, AppState, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

import { Bell, CallLog, Camera, CheckCircle, Phone, Settings, XCircle } from '../components/Icons';
import { Button, Screen } from '../components/ui';
import { getRuntimeAccess, openBatterySettings, requestAllFilesAccess, requestBatteryAccess } from '../operator';
import { checkPermissions, hasRequiredPermissions, openAppSettings, requestAllPermissions, type PermissionState } from '../permissions';
import { colors, fonts, radius, space, tint } from '../theme';

export interface PermissionsScreenProps {
  onReady: () => void;
  onClose?: () => void;
}

const ROWS = [
  { key: 'phone', title: 'Telefon holati', description: 'Kiruvchi va chiquvchi qo‘ng‘iroqlarni aniqlash', Icon: Phone },
  { key: 'callLog', title: 'Qo‘ng‘iroqlar tarixi', description: 'Raqam, vaqt va javobsiz qo‘ng‘iroqlarni aniqlash', Icon: CallLog },
  { key: 'camera', title: 'Kamera', description: 'POS ni bir marta QR-kod orqali qo‘shish', Icon: Camera },
  { key: 'notifications', title: 'Bildirishnomalar', description: 'Fonda ishlayotgan xizmat holatini ko‘rsatish', Icon: Bell },
] as const;

export function PermissionsScreen({ onReady, onClose }: PermissionsScreenProps) {
  const [state, setState] = useState<PermissionState | null>(null);
  const [access, setAccess] = useState<{ allFiles: boolean; battery: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempted, setAttempted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    const [permissions, special] = await Promise.allSettled([checkPermissions(), getRuntimeAccess()]);
    if (permissions.status === 'fulfilled') setState(permissions.value);
    else setError('Ruxsatlarni tekshirib bo‘lmadi. Qayta urinib ko‘ring.');
    if (special.status === 'fulfilled') setAccess(special.value);
    else setError('Fayl va batareya holatini tekshirib bo‘lmadi. Ilovaning yangi APK versiyasini o‘rnating.');
  }, []);

  useEffect(() => {
    void refresh();
    const listener = AppState.addEventListener('change', (next) => { if (next === 'active') void refresh(); });
    return () => listener.remove();
  }, [refresh]);

  const act = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try { await action(); await refresh(); }
    catch { setError('Sozlamani ochib bo‘lmadi. Telefon sozlamalaridan Smart POS Operator ilovasini tanlang.'); }
    finally { setBusy(false); }
  };

  const grant = async () => {
    setAttempted(true);
    await act(async () => { setState(await requestAllPermissions()); });
  };

  const ready = !!state && hasRequiredPermissions(state);
  const runtimeComplete = !!state && ROWS.every((row) => state[row.key]);
  const totalGranted = (state ? ROWS.filter((row) => state[row.key]).length : 0) + (access?.allFiles ? 1 : 0) + (access?.battery ? 1 : 0);

  return (
    <Screen>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>Ruxsatlar va batareya</Text>
        {onClose ? <TouchableOpacity accessibilityRole="button" accessibilityLabel="Orqaga" onPress={onClose} disabled={busy} style={styles.back}><Text style={styles.backText}>Orqaga</Text></TouchableOpacity> : null}
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.description}>Qo‘ng‘iroqlarni POS ga yuborish va ovoz yozuvlarini o‘qish uchun quyidagi ruxsatlarni bering. Ilova har safar haqiqiy ruxsat holatini tekshiradi.</Text>
        <View style={styles.progressRow}><Text style={styles.progressLabel}>Berilgan ruxsatlar</Text><Text style={[styles.progressLabel, { color: totalGranted === 6 ? colors.connected : colors.brand }]}>{totalGranted} / 6</Text></View>
        <View style={styles.progress}><View style={{ height: '100%', width: `${totalGranted / 6 * 100}%`, backgroundColor: totalGranted === 6 ? colors.connected : colors.brand, borderRadius: radius.pill }} /></View>
        {!state ? <ActivityIndicator color={colors.brand} /> : ROWS.map((row) => (
          <View key={row.key} style={styles.permissionRow}>
            <row.Icon size={22} color={state[row.key] ? colors.connected : colors.muted} />
            <View style={{ flex: 1 }}><Text style={styles.label}>{row.title}</Text><Text style={styles.help}>{row.description}</Text></View>
            <View accessible accessibilityLabel={state[row.key] ? 'Ruxsat berilgan' : 'Ruxsat berilmagan'}>{state[row.key] ? <CheckCircle /> : <XCircle />}</View>
          </View>
        ))}
        <SpecialPermission title="Barcha fayllarga kirish" description="Telefon saqlagan audio yozuvlarni topish uchun Android fayl ruxsati." granted={access?.allFiles ?? false} checked={access !== null} label="Fayllarga ruxsat berish" onPress={() => void act(requestAllFilesAccess)} disabled={busy} />
        <SpecialPermission title="Batareya cheklovini olib tashlash" description="Ekran o‘chganda ham xizmatning ishlashiga ruxsat bering." granted={access?.battery ?? false} checked={access !== null} label="Batareya ruxsatini berish" onPress={() => void act(requestBatteryAccess)} disabled={busy} />

        <View style={styles.samsungCard}>
          <Text style={styles.samsungTitle}>Samsung uchun qo‘shimcha sozlama</Text>
          <Text style={styles.description}>Sozlamalar → Batareya → Fonda foydalanish cheklovlari bo‘limida ilovani “Hech qachon uyquga ketmaydigan ilovalar” ro‘yxatiga qo‘shing. Ilova batareyasi uchun “Cheklanmagan” rejimini tanlang. Bo‘lim nomlari telefon tiliga qarab farq qilishi mumkin.</Text>
          <Button label="Batareya sozlamalari" variant="secondary" onPress={() => void act(openBatterySettings)} disabled={busy} />
          <Text style={styles.help}>Ilova qayta yoqilganda xizmatni tiklaydi. Android sozlamalaridagi “Majburan to‘xtatish”dan keyin ilovani qo‘lda ochish kerak. Telefon o‘chgan davrda xizmat ishlamaydi.</Text>
        </View>
        {attempted && !runtimeComplete ? <View style={styles.notice}><Text style={styles.description}>Agar ruxsat oynasi boshqa ochilmasa, ilova sozlamalarida ruxsatlarni yoqing. Kamera va bildirishnomalar uchun ham ruxsat berish tavsiya etiladi.</Text><Button label="Ilova sozlamalarini ochish" variant="secondary" onPress={() => void act(openAppSettings)} disabled={busy} /></View> : null}
        {ready && totalGranted < 6 ? <Text style={styles.help}>Qo‘ng‘iroqlar uchun asosiy ruxsatlar berildi. Qolgan ruxsatlar QR skaneri, yozuvlar va fonda ishlash uchun kerak.</Text> : null}
        {error ? <Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}
      </ScrollView>
      <View style={styles.footer}>
        {!runtimeComplete ? <Button label="Asosiy ruxsatlarni berish" onPress={() => void grant()} loading={busy} disabled={busy} /> : null}
        <Button label={onClose ? 'Tayyor' : 'Davom etish'} variant={runtimeComplete ? 'primary' : 'secondary'} onPress={onReady} disabled={!ready || busy} />
      </View>
    </Screen>
  );
}

function SpecialPermission({ title, description, granted, checked, label, onPress, disabled }: { title: string; description: string; granted: boolean; checked: boolean; label: string; onPress: () => void; disabled: boolean }) {
  return (
    <View style={styles.special}>
      <View style={styles.specialHeading}><Settings size={22} color={granted ? colors.connected : colors.warn} /><Text style={[styles.label, { flex: 1 }]}>{title}</Text>{granted ? <CheckCircle /> : <XCircle />}</View>
      <Text style={styles.description}>{description}</Text>
      <Text style={[styles.help, { color: granted ? colors.connected : colors.warn }]}>{!checked ? 'Holat olinmadi' : granted ? 'Ruxsat berilgan' : 'Ruxsat berilmagan'}</Text>
      {!granted ? <Button label={label} variant="secondary" onPress={onPress} disabled={disabled} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 8, gap: 8 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 23, flex: 1 },
  back: { minHeight: 44, paddingLeft: 8, justifyContent: 'center' },
  backText: { color: colors.brand, fontFamily: fonts.semibold, fontSize: 14 },
  content: { padding: space.lg, paddingTop: 6, paddingBottom: space.xl, gap: 10 },
  description: { color: colors.textSoft, fontFamily: fonts.regular, fontSize: 13, lineHeight: 20 },
  label: { color: colors.text, fontFamily: fonts.semibold, fontSize: 14 },
  help: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18, marginTop: 3 },
  progressRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 },
  progressLabel: { color: colors.muted, fontFamily: fonts.semibold, fontSize: 12 },
  progress: { height: 5, backgroundColor: colors.raised, borderRadius: radius.pill, overflow: 'hidden', marginBottom: 4 },
  permissionRow: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 13, borderRadius: radius.md, backgroundColor: colors.inset, borderWidth: 1, borderColor: colors.border },
  special: { padding: 14, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.surface, gap: 8 },
  specialHeading: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  samsungCard: { padding: 16, marginTop: 4, gap: 10, backgroundColor: tint.brandBg, borderRadius: radius.lg, borderWidth: 1, borderColor: tint.brandBorder },
  samsungTitle: { color: colors.brand, fontFamily: fonts.bold, fontSize: 16 },
  notice: { padding: 14, gap: 10, backgroundColor: colors.inset, borderRadius: radius.md },
  error: { color: colors.danger, fontFamily: fonts.medium, fontSize: 13, lineHeight: 20 },
  footer: { padding: space.lg, paddingBottom: 20, gap: 8, borderTopWidth: 1, borderTopColor: colors.border },
});
