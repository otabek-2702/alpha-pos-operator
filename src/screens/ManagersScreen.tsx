import { useState } from 'react';
import { Alert, KeyboardAvoidingView, Platform, ScrollView, Share, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';

import { Button, Screen } from '../components/ui';
import type { ManagerConfig, ManagerSchedule, OperatorOperations, ShiftConfig } from '../operator';
import { formatUzPhone, isUzbekMobile, managerInviteLink, managerShiftForWeek, pickContact, weekStart } from '../operator';
import { colors, fonts, radius, space, tint } from '../theme';

export interface ManagersScreenProps {
  managers: ManagerConfig[];
  shifts: ShiftConfig[];
  operations: OperatorOperations | null;
  onSave: (managers: ManagerConfig[]) => Promise<void>;
  /** Owner link for bot commands. */
  adminInvite?: string;
  onRenewAdmin: () => Promise<void>;
  onClose: () => void;
}

interface Draft {
  id: string;
  name: string;
  phone: string;
  rotating: boolean;
  shift: number;
  sms: boolean;
  telegram: boolean;
}

const WEEK_MS = 7 * 86_400_000;

function describeSchedule(schedule: ManagerSchedule, shifts: ShiftConfig[]): string {
  const name = (index: number | null) => shifts.find((s) => s.index === index)?.name ?? '—';
  if (schedule.type === 'fixed') return `Doimiy: ${name(schedule.shift)}`;
  const now = weekStart(Date.now());
  return `Har hafta almashadi · bu hafta ${name(managerShiftForWeek(schedule, now, shifts))}, keyingi hafta ${name(managerShiftForWeek(schedule, now + WEEK_MS, shifts))}`;
}

export function ManagersScreen({ managers, shifts, operations, onSave, adminInvite, onRenewAdmin, onClose }: ManagersScreenProps) {
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const chats = operations?.managerChats ?? {};
  const bot = operations?.botUsername ?? '';

  const persist = async (next: ManagerConfig[]) => {
    setBusy(true);
    setError(null);
    try { await onSave(next); return true; }
    catch (failure) {
      const message = (failure as { message?: unknown }).message;
      setError(typeof message === 'string' && message ? message : 'Saqlanmadi.');
      return false;
    } finally { setBusy(false); }
  };

  const startNew = () => {
    setError(null);
    setDraft({ id: `m${Date.now().toString(36)}`, name: '', phone: '', rotating: true, shift: shifts[0]?.index ?? 1, sms: true, telegram: true });
  };

  const edit = (manager: ManagerConfig) => {
    const week = weekStart(Date.now());
    setError(null);
    setDraft({
      id: manager.id, name: manager.name, phone: manager.phone, sms: manager.sms, telegram: manager.telegram,
      rotating: manager.schedule.type === 'rotating',
      shift: managerShiftForWeek(manager.schedule, week, shifts) ?? shifts[0]?.index ?? 1,
    });
  };

  const fromContacts = async () => {
    try {
      const contact = await pickContact();
      if (contact && draft) setDraft({ ...draft, phone: formatUzPhone(contact.phone), name: draft.name || contact.name });
    } catch { setError('Kontaktlarni ochib bo‘lmadi.'); }
  };

  const saveDraft = async () => {
    if (!draft) return;
    if (!draft.name.trim()) { setError('Menejer ismini kiriting.'); return; }
    if (draft.phone.trim() && !isUzbekMobile(draft.phone)) { setError('Raqam +998 bilan boshlanadigan mobil raqam bo‘lsin.'); return; }
    if (draft.sms && !draft.phone.trim()) { setError('SMS uchun telefon raqamini kiriting yoki SMSni o‘chiring.'); return; }
    // The anchor is this week: the chosen shift is "this week's shift".
    const schedule: ManagerSchedule = draft.rotating
      ? { type: 'rotating', anchorWeekStart: weekStart(Date.now()), anchorShift: draft.shift }
      : { type: 'fixed', shift: draft.shift };
    const existing = managers.find((m) => m.id === draft.id);
    const next: ManagerConfig = { ...existing, id: draft.id, name: draft.name.trim(), phone: draft.phone.trim(), schedule, sms: draft.sms, telegram: draft.telegram };
    const list = existing ? managers.map((m) => (m.id === draft.id ? next : m)) : [...managers, next];
    if (await persist(list)) setDraft(null);
  };

  const remove = (manager: ManagerConfig) => {
    Alert.alert('Menejerni o‘chirish', `${manager.name} endi ogohlantirish olmaydi.`, [
      { text: 'Bekor qilish', style: 'cancel' },
      { text: 'O‘chirish', style: 'destructive', onPress: () => { void persist(managers.filter((m) => m.id !== manager.id)); } },
    ]);
  };

  const share = async (manager: ManagerConfig) => {
    const link = managerInviteLink(bot, manager.invite);
    if (!link) { setError('Bot ma’lumoti hali olinmadi. Internetni tekshirib, biroz kuting.'); return; }
    try {
      await Share.share({ message: `Smart Food: ${manager.name}, qo‘ng‘iroqlar bo‘yicha ogohlantirishlarni olish uchun havolani oching va “Start” tugmasini bosing:\n${link}` });
    } catch { setError('Havolani ulashib bo‘lmadi.'); }
  };

  const shareAdmin = async () => {
    const link = managerInviteLink(bot, adminInvite);
    if (!link) { setError('Bot ma’lumoti hali olinmadi. Internetni tekshirib, biroz kuting.'); return; }
    try {
      await Share.share({ message: `Smart Food Operator boshqaruvi (faqat egasi uchun). Havolani oching va “Start” ni bosing:\n${link}` });
    } catch { setError('Havolani ulashib bo‘lmadi.'); }
  };

  const renewAdmin = () => {
    Alert.alert('Yangi boshqaruv havolasi', 'Eski havola bilan ulangan barcha hisoblar boshqaruvdan uziladi.', [
      { text: 'Bekor qilish', style: 'cancel' },
      {
        text: 'Yangilash', style: 'destructive', onPress: () => {
          setBusy(true);
          setError(null);
          void onRenewAdmin().catch(() => setError('Havola yangilanmadi.')).finally(() => setBusy(false));
        },
      },
    ]);
  };

  if (draft) {
    return (
      <Screen>
        <View style={styles.header}>
          <Text accessibilityRole="header" style={styles.title}>{managers.some((m) => m.id === draft.id) ? 'Menejerni tahrirlash' : 'Yangi menejer'}</Text>
          <TouchableOpacity accessibilityRole="button" onPress={() => setDraft(null)} disabled={busy} style={styles.back}><Text style={styles.link}>Bekor qilish</Text></TouchableOpacity>
        </View>
        <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
            <Text style={styles.label}>Ism</Text>
            <TextInput accessibilityLabel="Menejer ismi" value={draft.name} onChangeText={(name) => setDraft({ ...draft, name })} maxLength={60} style={styles.input} placeholder="Masalan: Aziz" placeholderTextColor={colors.muted2} />
            <Text style={styles.label}>Telefon raqami</Text>
            <TextInput accessibilityLabel="Menejer telefoni" value={draft.phone} onChangeText={(phone) => setDraft({ ...draft, phone })} keyboardType="phone-pad" maxLength={20} style={styles.input} placeholder="+998 90 123 45 67" placeholderTextColor={colors.muted2} />
            <Button label="Kontaktlardan tanlash" variant="secondary" onPress={() => void fromContacts()} disabled={busy} />

            <Text style={styles.label}>Ish jadvali</Text>
            <View style={styles.segment}>
              <Chip label="Har hafta almashadi" selected={draft.rotating} onPress={() => setDraft({ ...draft, rotating: true })} />
              <Chip label="Doimiy" selected={!draft.rotating} onPress={() => setDraft({ ...draft, rotating: false })} />
            </View>
            <Text style={styles.help}>{draft.rotating ? 'Bu hafta qaysi smenada ishlaydi? Keyingi yakshanbadan boshlab smena avtomatik almashadi.' : 'Menejer har doim shu smenada ishlaydi.'}</Text>
            <View style={styles.segment}>
              {shifts.map((shift) => <Chip key={shift.index} label={`${shift.name} ${shift.start}–${shift.end}`} selected={draft.shift === shift.index} onPress={() => setDraft({ ...draft, shift: shift.index })} />)}
            </View>

            <View style={styles.toggle}>
              <View style={{ flex: 1 }}><Text style={styles.label}>SMS ogohlantirish</Text><Text style={styles.help}>Javobsiz qo‘ng‘iroqqa belgilangan vaqtda qayta qo‘ng‘iroq qilinmasa</Text></View>
              <Switch accessibilityLabel="SMS ogohlantirish" value={draft.sms} onValueChange={(sms) => setDraft({ ...draft, sms })} trackColor={{ false: colors.borderStrong, true: colors.brandDark }} thumbColor={draft.sms ? colors.brand : colors.muted} />
            </View>
            <View style={styles.toggle}>
              <View style={{ flex: 1 }}><Text style={styles.label}>Telegram ogohlantirish</Text><Text style={styles.help}>Ogohlantirishlar, yo‘qotilgan mijozlar va smena hisoboti</Text></View>
              <Switch accessibilityLabel="Telegram ogohlantirish" value={draft.telegram} onValueChange={(telegram) => setDraft({ ...draft, telegram })} trackColor={{ false: colors.borderStrong, true: colors.brandDark }} thumbColor={draft.telegram ? colors.brand : colors.muted} />
            </View>
            {error ? <Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}
          </ScrollView>
          <View style={styles.footer}><Button label="Saqlash" onPress={() => void saveDraft()} loading={busy} disabled={busy} /></View>
        </KeyboardAvoidingView>
      </Screen>
    );
  }

  return (
    <Screen>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>Menejerlar</Text>
        <TouchableOpacity accessibilityRole="button" onPress={onClose} disabled={busy} style={styles.back}><Text style={styles.link}>Orqaga</Text></TouchableOpacity>
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.notice}>
          <Text style={styles.body}>Menejer faqat o‘z smenasidagi ogohlantirishlarni oladi: javobsiz qo‘ng‘iroq (SMS va Telegram), yo‘qotilgan mijoz va smena hisoboti. Telegram uchun menejerga shaxsiy havolani yuboring — u “Start” tugmasini bossa ulanadi.</Text>
        </View>
        <View style={styles.card}>
          <Text style={styles.name}>Telegram orqali boshqarish</Text>
          <Text style={styles.body}>Egasi shu havolani ochsa, botdagi buyruqlar bilan telefon sozlamalarini o‘zgartira oladi: menejerlar, raqamlar, guruhlar va vaqtlar. Havolani faqat o‘zingizga yuboring.</Text>
          <Text style={styles.help}>Buyruqlar ro‘yxati: botga /boshqaruv yozing.</Text>
          <View style={styles.actions}>
            <Button label="Havolani yuborish" variant="secondary" height={40} onPress={() => void shareAdmin()} disabled={busy || !adminInvite} style={{ flex: 1 }} />
            <TouchableOpacity accessibilityRole="button" onPress={renewAdmin} disabled={busy} style={styles.small}><Text style={styles.danger}>Yangi havola</Text></TouchableOpacity>
          </View>
        </View>
        {managers.length === 0 ? <Text style={styles.help}>Hali menejer qo‘shilmagan.</Text> : null}
        {managers.map((manager) => {
          const linked = !!chats[manager.id];
          return (
            <View key={manager.id} style={styles.card}>
              <Text style={styles.name}>{manager.name}</Text>
              {manager.phone ? <Text style={styles.body}>{formatUzPhone(manager.phone)}</Text> : null}
              <Text style={styles.help}>{describeSchedule(manager.schedule, shifts)}</Text>
              <Text style={styles.help}>
                {manager.sms ? 'SMS: yoqilgan' : 'SMS: o‘chirilgan'} · {manager.telegram ? (linked ? '🟢 Telegram ulangan' : '🟡 Telegram ulanmagan') : 'Telegram: o‘chirilgan'}
              </Text>
              <View style={styles.actions}>
                {manager.telegram ? <Button label={linked ? 'Havolani qayta yuborish' : 'Telegram havolasini yuborish'} variant="secondary" height={40} onPress={() => void share(manager)} disabled={busy} style={{ flex: 1 }} /> : null}
              </View>
              <View style={styles.actions}>
                <TouchableOpacity accessibilityRole="button" onPress={() => edit(manager)} disabled={busy} style={styles.small}><Text style={styles.link}>Tahrirlash</Text></TouchableOpacity>
                <TouchableOpacity accessibilityRole="button" onPress={() => remove(manager)} disabled={busy} style={styles.small}><Text style={styles.danger}>O‘chirish</Text></TouchableOpacity>
              </View>
            </View>
          );
        })}
        {error ? <Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}
      </ScrollView>
      <View style={styles.footer}><Button label="Menejer qo‘shish" onPress={startNew} disabled={busy || managers.length >= 30} /></View>
    </Screen>
  );
}

function Chip({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  return (
    <TouchableOpacity accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.chip, selected && styles.chipSelected]}>
      <Text style={[styles.chipText, selected && { color: colors.text }]}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 6 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 23, flex: 1 },
  back: { minHeight: 44, justifyContent: 'center', paddingLeft: 8 },
  link: { color: colors.brand, fontFamily: fonts.semibold, fontSize: 14 },
  danger: { color: colors.danger, fontFamily: fonts.semibold, fontSize: 14 },
  content: { padding: space.lg, gap: 10, paddingBottom: space.xl },
  notice: { backgroundColor: tint.brandBg, borderWidth: 1, borderColor: tint.brandBorder, borderRadius: radius.lg, padding: 14 },
  card: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, padding: 14, gap: 4 },
  name: { color: colors.text, fontFamily: fonts.semibold, fontSize: 16 },
  body: { color: colors.textSoft, fontFamily: fonts.regular, fontSize: 13, lineHeight: 19 },
  label: { color: colors.text, fontFamily: fonts.semibold, fontSize: 14, marginTop: 6 },
  help: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18 },
  input: { backgroundColor: colors.inset, color: colors.text, borderWidth: 1, borderColor: colors.borderStrong, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10, minHeight: 46, fontFamily: fonts.regular, fontSize: 14 },
  segment: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { paddingHorizontal: 12, paddingVertical: 9, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.inset },
  chipSelected: { borderColor: colors.brand, backgroundColor: tint.brandBg },
  chipText: { color: colors.textSoft, fontFamily: fonts.medium, fontSize: 13 },
  toggle: { flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 8 },
  actions: { flexDirection: 'row', gap: 8, marginTop: 6 },
  small: { minHeight: 40, justifyContent: 'center', paddingRight: 14 },
  error: { color: colors.danger, fontFamily: fonts.medium, fontSize: 13, lineHeight: 20 },
  footer: { padding: space.lg, paddingBottom: 20, borderTopWidth: 1, borderTopColor: colors.border },
});
