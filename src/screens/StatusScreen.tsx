import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';

import { Bell, CallLog, Clock, DotsVertical, PhoneFill, Rescan, Send, Settings } from '../components/Icons';
import { Button, Label, Screen } from '../components/ui';
import { UpdateBanner } from '../components/UpdateBanner';
import type { AppUpdates } from '../hooks/useAppUpdates';
import type { PosRole, RuntimeSnapshot, SavedPos } from '../operator';
import { colors, fonts, radius, space, tint } from '../theme';

export interface StatusScreenProps {
  snapshot: RuntimeSnapshot | null;
  targets: SavedPos[];
  permissionGranted: boolean;
  updates: AppUpdates;
  onAdd: () => void;
  onRemove: (id: string) => void;
  onRoleChange: (id: string, role: PosRole) => void;
  onSendTest: () => void;
  onOpenPermissions: () => void;
  onOpenTelegram: () => void;
  onOpenHistory: () => void;
  onOpenSupport: () => void;
  onOpenSchedule: () => void;
  onOpenManagers: () => void;
  managerCount: number;
  testing?: boolean;
}

export function StatusScreen(props: StatusScreenProps) {
  const { snapshot, targets, permissionGranted } = props;
  const telegram = snapshot?.telegram;
  const needsSetup = targets.length === 0 && !telegram?.enabled && !telegram?.sendCallStats;
  const connected = targets.filter((target) => snapshot?.targets.some((item) => item.id === target.id && item.status === 'connected')).length;
  const monitoringFailed = !!snapshot?.running && snapshot.phonePermission === false && permissionGranted;
  const running = !!snapshot?.running && permissionGranted && !monitoringFailed;
  const allConnected = running && targets.length > 0 && connected === targets.length;
  const status = !permissionGranted ? 'Ruxsat kerak' : !snapshot ? 'Tekshirilmoqda' : monitoringFailed ? 'Kuzatuv ishlamayapti' : needsSetup ? 'POS qo‘shing' : !running ? 'Ishlamayapti' : targets.length === 0 ? 'Ishlayapti' : connected === 0 ? 'Ulanish kutilmoqda' : !allConnected ? 'Qisman ulangan' : 'Ishlayapti';
  const tone = allConnected || (running && targets.length === 0 && !needsSetup) ? colors.connected : needsSetup ? colors.brand : !permissionGranted || (snapshot && !running) ? colors.danger : colors.warn;
  const statusDescription = !permissionGranted
    ? 'Qo‘ng‘iroqlarni yuborish uchun telefon va qo‘ng‘iroqlar tarixiga ruxsat bering.'
    : !snapshot ? 'Telefon xizmati holati olinmoqda…'
    : monitoringFailed ? 'Telefon qo‘ng‘iroqlarini kuzatish boshlanmadi. Ruxsatlarni tekshirib, ilovani qayta oching.'
    : needsSetup ? 'POS dagi Operator tugmasini bosib turing va QR-kodni bir marta skanerlang.'
    : !running ? 'Telefon xizmati to‘xtagan. Ruxsatlar va batareya sozlamalarini tekshiring.'
    : targets.length === 0 ? 'Telegramga yuborish xizmati ishlayapti. Qo‘ng‘iroqlarni POSga yuborish uchun POS qo‘shing.'
    : `${connected} / ${targets.length} POS ulangan. Har bir qo‘ng‘iroq barcha saqlangan POS larga yuboriladi.`;
  const telegramDescription = telegram?.lastError || telegram?.statsError ? 'Yuborishda muammo · sozlamalarni tekshiring' : telegram?.enabled && telegram.configured ? `${telegram.sent} ta yozuv yuborildi · ${telegram.pending} ta navbatda` : telegram?.sendCallStats ? 'Qo‘ng‘iroqlar hisoboti yoqilgan' : !telegram?.configured ? 'Bot, guruhlar va yozuvlar papkasini sozlang' : 'Yuborish o‘chirilgan';

  return (
    <Screen>
      <View style={styles.header}>
        <View style={styles.brand}>
          <LinearGradient colors={[colors.brand, colors.brandDark]} style={styles.appIcon}><PhoneFill size={18} /></LinearGradient>
          <View>
            <Text style={styles.brandTitle}>Smart POS Operator</Text>
            <Text style={styles.brandSubtitle}>Telefon va POS birga ishlaydi</Text>
          </View>
        </View>
        <TouchableOpacity accessibilityRole="button" accessibilityLabel="Yordam va yangilanishlar" onPress={props.onOpenSupport} style={styles.menu}><DotsVertical size={24} /></TouchableOpacity>
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        <UpdateBanner updates={props.updates} />
        <LinearGradient colors={[allConnected ? tint.connectedBg : colors.raised, colors.inset]} style={[styles.statusCard, { borderColor: tone }]}>
          <View style={styles.statusLabel}><View style={[styles.dot, { backgroundColor: tone }]} /><Label color={tone}>Xizmat holati</Label></View>
          <Text accessibilityRole="header" accessibilityLiveRegion="polite" style={[styles.statusTitle, { color: tone }]}>{status}</Text>
          <Text style={styles.description}>{statusDescription}</Text>
          {snapshot?.lastError ? <Text style={[styles.description, { color: colors.warn, marginTop: 8 }]}>{snapshot.lastError}</Text> : null}
          {running ? <Text style={styles.backgroundNote}>Ilova yopilganda ham xizmat fonda ishlaydi.</Text> : null}
        </LinearGradient>

        <View style={styles.sectionHeading}><Text style={styles.sectionTitle}>Saqlangan POS lar</Text><Text style={styles.count}>{targets.length}</Text></View>
        {targets.length === 0 ? (
          <View style={styles.empty}>
            <Rescan size={28} color={colors.brand} />
            <Text style={styles.emptyTitle}>Birinchi POS ni qo‘shing</Text>
            <Text style={[styles.description, { textAlign: 'center' }]}>Telefon va POS bir xil Wi-Fi yoki mahalliy tarmoqqa ulangan bo‘lsin. Keyingi safar ulanish avtomatik tiklanadi.</Text>
          </View>
        ) : targets.map((target) => {
          const runtime = snapshot?.targets.find((item) => item.id === target.id);
          const state = running ? runtime?.status ?? 'disconnected' : 'disconnected';
          const color = state === 'connected' ? colors.connected : state === 'connecting' ? colors.warn : colors.muted;
          const label = state === 'connected' ? 'Ulangan' : state === 'connecting' ? 'Ulanmoqda…' : 'Ulanmagan';
          return (
            <View key={target.id} style={styles.posCard}>
              <View style={{ flex: 1, minWidth: 0 }}>
                <Text numberOfLines={2} style={styles.posName}>{target.name}</Text>
                <View style={styles.posState}><View style={[styles.smallDot, { backgroundColor: color }]} /><Text style={[styles.posStateText, { color }]}>{label}</Text></View>
                <Text numberOfLines={1} style={styles.address}>{(runtime?.url ?? target.url).split('?')[0]}</Text>
                {runtime?.error ? <Text style={[styles.description, { color: colors.muted, marginTop: 4 }]}>{runtime.error}</Text> : null}
                <View style={styles.roles}>
                  {(['operator', 'cashier'] as const).map((role) => {
                    const selected = (target.role ?? 'operator') === role;
                    return (
                      <TouchableOpacity key={role} accessibilityRole="button" accessibilityState={{ selected }}
                        accessibilityLabel={`${target.name}: ${role === 'operator' ? 'Operator' : 'Kassa'}`}
                        onPress={() => { if (!selected) props.onRoleChange(target.id, role); }} style={[styles.role, selected && styles.roleSelected]}>
                        <Text style={[styles.roleText, selected && { color: colors.text }]}>{role === 'operator' ? 'Operator' : 'Kassa'}</Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
                <Text style={styles.roleHint}>{(target.role ?? 'operator') === 'operator' ? 'Qo‘ng‘iroq oynasi ochiladi' : 'Oyna ochilmaydi, raqam tez kiritish uchun beriladi'}</Text>
              </View>
              <TouchableOpacity accessibilityRole="button" accessibilityLabel={`${target.name} POS ni o‘chirish`} onPress={() => props.onRemove(target.id)} style={styles.removeButton}><Text style={styles.removeText}>O‘chirish</Text></TouchableOpacity>
            </View>
          );
        })}

        <Text style={[styles.sectionTitle, { marginTop: 12 }]}>Sozlamalar</Text>
        <View style={styles.settings}>
          <SettingsRow icon={<Settings size={20} color={permissionGranted ? colors.connected : colors.warn} />} title="Ruxsatlar va batareya" description={permissionGranted ? 'Fonda ishlash va fayllarga kirishni tekshirish' : 'Qo‘ng‘iroqlar uchun ruxsatlar kerak'} onPress={props.onOpenPermissions} />
          <View style={styles.divider} />
          <SettingsRow icon={<Send size={20} color={colors.brand} />} title="Telegram yozuvlari va hisobot" description={telegramDescription} onPress={props.onOpenTelegram} />
          <View style={styles.divider} />
          <SettingsRow icon={<Clock size={20} color={colors.brand} />} title="Ish tartibi va SMS" description="Smenalar, yopiq vaqtdagi SMS va ogohlantirish vaqtlari" onPress={props.onOpenSchedule} />
          <View style={styles.divider} />
          <SettingsRow icon={<Bell size={20} color={colors.brand} />} title="Menejerlar" description={props.managerCount ? `${props.managerCount} ta menejer · SMS va Telegram ogohlantirishlari` : 'Javobsiz qo‘ng‘iroqlar haqida xabar oladiganlar'} onPress={props.onOpenManagers} />
          <View style={styles.divider} />
          <SettingsRow icon={<CallLog size={20} color={colors.brand} />} title="Ishlash tarixi" description="Xizmat yoqilgan va to‘xtagan vaqtlar" onPress={props.onOpenHistory} />
        </View>
      </ScrollView>

      <View style={styles.footer}>
        <Button label="POS qo‘shish" icon={<Rescan color={colors.onBrand} />} onPress={props.onAdd} style={{ flex: 1 }} />
        <Button label="Sinov yuborish" variant="secondary" onPress={props.onSendTest} disabled={!running || targets.length === 0 || props.testing} loading={props.testing} style={{ flex: 1 }} />
      </View>
    </Screen>
  );
}

function SettingsRow({ icon, title, description, onPress }: { icon: React.ReactNode; title: string; description: string; onPress: () => void }) {
  return (
    <TouchableOpacity accessibilityRole="button" accessibilityLabel={`${title}. ${description}`} onPress={onPress} activeOpacity={0.75} style={styles.settingsRow}>
      {icon}<View style={{ flex: 1 }}><Text style={styles.settingsTitle}>{title}</Text><Text style={styles.settingsDescription}>{description}</Text></View><Text style={{ color: colors.muted, fontSize: 24 }}>›</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 12 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 10, flex: 1 },
  appIcon: { width: 38, height: 38, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  brandTitle: { color: colors.text, fontFamily: fonts.bold, fontSize: 17 },
  brandSubtitle: { color: colors.muted, fontFamily: fonts.regular, fontSize: 11 },
  menu: { minHeight: 44, width: 44, alignItems: 'center', justifyContent: 'center' },
  content: { paddingHorizontal: space.lg, paddingBottom: space.xl, gap: 10 },
  statusCard: { padding: 20, borderRadius: radius.xl, borderWidth: 1, marginBottom: 8 },
  statusLabel: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  dot: { width: 9, height: 9, borderRadius: 5 },
  statusTitle: { fontFamily: fonts.bold, fontSize: 34, lineHeight: 42, marginTop: 12, marginBottom: 5 },
  description: { color: colors.textSoft, fontFamily: fonts.regular, fontSize: 13, lineHeight: 20 },
  backgroundNote: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18, marginTop: 14 },
  sectionHeading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  sectionTitle: { color: colors.text, fontFamily: fonts.bold, fontSize: 17, marginBottom: 2 },
  count: { color: colors.muted, fontFamily: fonts.monoSemibold, fontSize: 13 },
  empty: { alignItems: 'center', gap: 8, padding: 22, backgroundColor: colors.inset, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border, borderStyle: 'dashed' },
  emptyTitle: { color: colors.text, fontFamily: fonts.semibold, fontSize: 16 },
  posCard: { flexDirection: 'row', alignItems: 'center', gap: 8, padding: 14, backgroundColor: colors.surface, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border },
  posName: { color: colors.text, fontFamily: fonts.semibold, fontSize: 16 },
  posState: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 5 },
  smallDot: { width: 6, height: 6, borderRadius: 3 },
  posStateText: { fontFamily: fonts.medium, fontSize: 12 },
  address: { color: colors.muted2, fontFamily: fonts.mono, fontSize: 10, marginTop: 5 },
  roles: { flexDirection: 'row', gap: 6, marginTop: 10 },
  role: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.inset },
  roleSelected: { borderColor: colors.brand, backgroundColor: tint.brandBg },
  roleText: { color: colors.muted, fontFamily: fonts.semibold, fontSize: 12 },
  roleHint: { color: colors.muted, fontFamily: fonts.regular, fontSize: 11, marginTop: 4 },
  removeButton: { minHeight: 44, paddingHorizontal: 10, justifyContent: 'center' },
  removeText: { color: colors.danger, fontFamily: fonts.medium, fontSize: 12 },
  settings: { backgroundColor: colors.inset, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border },
  settingsRow: { paddingHorizontal: 14, paddingVertical: 15, flexDirection: 'row', alignItems: 'center', gap: 12 },
  settingsTitle: { color: colors.text, fontFamily: fonts.semibold, fontSize: 14 },
  settingsDescription: { color: colors.muted, fontFamily: fonts.regular, fontSize: 11, lineHeight: 16, marginTop: 3 },
  divider: { height: 1, backgroundColor: colors.border, marginLeft: 46 },
  footer: { flexDirection: 'row', gap: 10, padding: space.lg, paddingBottom: 20, borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.bg },
});
