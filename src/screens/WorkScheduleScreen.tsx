import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';

import { Button, Screen } from '../components/ui';
import type { AlertConfig, ClosedSmsConfig, OperatorOperations, ShiftConfig } from '../operator';
import { DEFAULT_ALERTS, DEFAULT_CLOSED_SMS, DEFAULT_SHIFTS, isClock } from '../operator';
import { colors, fonts, radius, space, tint } from '../theme';

export interface WorkScheduleScreenProps {
  shifts: ShiftConfig[];
  closedSms: ClosedSmsConfig;
  alerts: AlertConfig;
  operations: OperatorOperations | null;
  onSave: (value: { shifts: ShiftConfig[]; closedSms: ClosedSmsConfig; alerts: AlertConfig }) => Promise<void>;
  onOpenPermissions: () => void;
  onClose: () => void;
}

const minutes = (value: string) => { const [h, m] = value.split(':').map(Number); return h! * 60 + m!; };

/** Minutes of the day that no shift covers, shown as "HH:MM–HH:MM". */
function closedRanges(shifts: ShiftConfig[]): string[] {
  const covered = new Array<boolean>(24 * 60).fill(false);
  for (const shift of shifts) {
    if (!isClock(shift.start) || !isClock(shift.end)) continue;
    const start = minutes(shift.start);
    const end = minutes(shift.end);
    const length = end > start ? end - start : 24 * 60 - start + end;
    for (let i = 0; i < length; i++) covered[(start + i) % (24 * 60)] = true;
  }
  const ranges: string[] = [];
  const clock = (m: number) => `${String(Math.floor(m / 60) % 24).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
  let i = 0;
  while (i < 24 * 60) {
    if (covered[i]) { i++; continue; }
    const from = i;
    while (i < 24 * 60 && !covered[i]) i++;
    ranges.push(`${clock(from)}–${clock(i)}`);
  }
  return ranges;
}

export function WorkScheduleScreen({ shifts: savedShifts, closedSms, alerts, operations, onSave, onOpenPermissions, onClose }: WorkScheduleScreenProps) {
  const [shifts, setShifts] = useState<ShiftConfig[]>(savedShifts.length ? savedShifts : DEFAULT_SHIFTS);
  const [smsEnabled, setSmsEnabled] = useState(closedSms.enabled);
  const [smsText, setSmsText] = useState(closedSms.text || DEFAULT_CLOSED_SMS);
  const [managerAfter, setManagerAfter] = useState(String(alerts.managerAfterMinutes ?? DEFAULT_ALERTS.managerAfterMinutes));
  const [lostAfter, setLostAfter] = useState(String(alerts.lostAfterMinutes ?? DEFAULT_ALERTS.lostAfterMinutes));
  const [smsCap, setSmsCap] = useState(String(alerts.smsDailyCap ?? DEFAULT_ALERTS.smsDailyCap));
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const changed = () => { setSaved(false); setError(null); };

  const updateShift = (index: number, patch: Partial<ShiftConfig>) => {
    setShifts((list) => list.map((shift, i) => (i === index ? { ...shift, ...patch } : shift)));
    changed();
  };
  const addShift = () => {
    const next = Math.max(0, ...shifts.map((s) => s.index)) + 1;
    setShifts((list) => [...list, { index: next, name: `${next}-smena`, start: '08:00', end: '17:00' }]);
    changed();
  };
  const removeShift = (index: number) => { setShifts((list) => list.filter((_, i) => i !== index)); changed(); };

  const closed = closedRanges(shifts);
  const smsLength = smsText.length;

  const save = async () => {
    const bad = shifts.find((s) => !isClock(s.start) || !isClock(s.end) || !s.name.trim());
    if (bad) { setError('Har bir smenaga nom va vaqt kiriting (masalan 08:00).'); return; }
    const toInt = (value: string, min: number, max: number) => {
      const n = Number(value);
      return Number.isInteger(n) && n >= min && n <= max ? n : null;
    };
    const manager = toInt(managerAfter, 1, 60);
    const lost = toInt(lostAfter, 1, 240);
    const cap = toInt(smsCap, 0, 500);
    if (manager === null || lost === null || cap === null) { setError('Vaqtlar: 1–60 va 1–240 daqiqa, SMS chegarasi 0–500.'); return; }
    if (lost <= manager) { setError('“Yo‘qotilgan” vaqti menejer ogohlantirishidan keyin bo‘lishi kerak.'); return; }
    if (!smsText.trim() || smsText.length > 300) { setError('SMS matni 1–300 belgi bo‘lsin.'); return; }
    setBusy(true);
    setError(null);
    try {
      await onSave({
        shifts: shifts.map((s) => ({ ...s, name: s.name.trim(), start: s.start.trim().padStart(5, '0'), end: s.end.trim().padStart(5, '0') })),
        closedSms: { enabled: smsEnabled, text: smsText.trim() },
        alerts: { managerAfterMinutes: manager, lostAfterMinutes: lost, smsDailyCap: cap },
      });
      setSaved(true);
    } catch (failure) {
      const message = (failure as { message?: unknown }).message;
      setError(typeof message === 'string' && message ? message : 'Sozlamalar saqlanmadi.');
    } finally { setBusy(false); }
  };

  return (
    <Screen>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>Ish tartibi</Text>
        <TouchableOpacity accessibilityRole="button" onPress={onClose} disabled={busy} style={styles.back}><Text style={styles.link}>Orqaga</Text></TouchableOpacity>
      </View>
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
        <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
          <Text style={styles.section}>Smenalar</Text>
          {shifts.map((shift, index) => (
            <View key={`${shift.index}-${index}`} style={styles.card}>
              <View style={styles.row}>
                <TextInput accessibilityLabel="Smena nomi" value={shift.name} onChangeText={(name) => updateShift(index, { name })} maxLength={40} style={[styles.input, { flex: 1 }]} placeholderTextColor={colors.muted2} />
                {shifts.length > 1 ? <TouchableOpacity accessibilityRole="button" onPress={() => removeShift(index)} style={styles.remove}><Text style={styles.danger}>O‘chirish</Text></TouchableOpacity> : null}
              </View>
              <View style={styles.row}>
                <TextInput accessibilityLabel={`${shift.name} boshlanishi`} value={shift.start} onChangeText={(start) => updateShift(index, { start })} placeholder="08:00" keyboardType="numbers-and-punctuation" maxLength={5} style={[styles.input, styles.time]} placeholderTextColor={colors.muted2} />
                <Text style={styles.body}>dan</Text>
                <TextInput accessibilityLabel={`${shift.name} tugashi`} value={shift.end} onChangeText={(end) => updateShift(index, { end })} placeholder="17:00" keyboardType="numbers-and-punctuation" maxLength={5} style={[styles.input, styles.time]} placeholderTextColor={colors.muted2} />
                <Text style={styles.body}>gacha</Text>
              </View>
            </View>
          ))}
          {shifts.length < 6 ? <Button label="Smena qo‘shish" variant="secondary" onPress={addShift} disabled={busy} /> : null}
          <View style={styles.notice}>
            <Text style={styles.noticeTitle}>{closed.length ? `Kafe yopiq: ${closed.join(', ')}` : 'Kafe kun bo‘yi ochiq'}</Text>
            <Text style={styles.help}>Yopiq vaqtdagi qo‘ng‘iroqlar “yo‘qotilgan” hisoblanmaydi va ertalabki smena hisobotida ko‘rsatiladi. Har bir smena tugaganda hisobot guruhga yuboriladi va qadab qo‘yiladi.</Text>
          </View>

          <Text style={styles.section}>Yopiq vaqtdagi SMS</Text>
          <View style={styles.card}>
            <View style={styles.row}>
              <View style={{ flex: 1 }}>
                <Text style={styles.label}>Qo‘ng‘iroq qilganlarga SMS yuborish</Text>
                <Text style={styles.help}>Faqat O‘zbekiston mobil raqamlariga, har yopiq davrda bir marta.</Text>
              </View>
              <Switch accessibilityLabel="Yopiq vaqtdagi SMS" value={smsEnabled} onValueChange={(value) => { setSmsEnabled(value); changed(); }} trackColor={{ false: colors.borderStrong, true: colors.brandDark }} thumbColor={smsEnabled ? colors.brand : colors.muted} />
            </View>
            <TextInput accessibilityLabel="SMS matni" value={smsText} onChangeText={(text) => { setSmsText(text); changed(); }} multiline maxLength={300} style={[styles.input, { minHeight: 90, textAlignVertical: 'top' }]} placeholderTextColor={colors.muted2} />
            <Text style={styles.help}>{smsLength} / 160 belgi{smsLength > 160 ? ' · 2 ta SMS bo‘lib ketadi' : ''}. Tutuq belgisi uchun oddiy ' dan foydalaning.</Text>
            {operations && operations.smsPermission === false ? (
              <TouchableOpacity accessibilityRole="button" onPress={onOpenPermissions}><Text style={styles.warn}>SMS yuborish ruxsati berilmagan — Ruxsatlar bo‘limini oching</Text></TouchableOpacity>
            ) : null}
            {typeof operations?.smsToday === 'number' ? <Text style={styles.help}>Bugun yuborilgan SMS: {operations.smsToday}</Text> : null}
          </View>

          <Text style={styles.section}>Ogohlantirishlar</Text>
          <View style={styles.card}>
            <Field label="Menejerga xabar (javobsiz qo‘ng‘iroqdan keyin, daqiqa)" value={managerAfter} onChange={(v) => { setManagerAfter(v); changed(); }} />
            <Field label="“Yo‘qotilgan mijoz” (daqiqa)" value={lostAfter} onChange={(v) => { setLostAfter(v); changed(); }} />
            <Field label="Kunlik SMS chegarasi" value={smsCap} onChange={(v) => { setSmsCap(v); changed(); }} />
          </View>
          {error ? <Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}
          {saved ? <Text accessibilityLiveRegion="polite" style={styles.success}>Saqlandi.</Text> : null}
        </ScrollView>
        <View style={styles.footer}>
          <Button label={saved ? 'Saqlandi' : 'Saqlash'} onPress={() => void save()} loading={busy} disabled={busy || saved} />
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <View style={styles.row}>
      <Text style={[styles.body, { flex: 1 }]}>{label}</Text>
      <TextInput accessibilityLabel={label} value={value} onChangeText={(v) => onChange(v.replace(/\D/g, ''))} keyboardType="number-pad" maxLength={3} style={[styles.input, styles.number]} />
    </View>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 6 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 23, flex: 1 },
  back: { minHeight: 44, justifyContent: 'center', paddingLeft: 8 },
  link: { color: colors.brand, fontFamily: fonts.semibold, fontSize: 14 },
  content: { padding: space.lg, gap: 12, paddingBottom: space.xl },
  section: { color: colors.text, fontFamily: fonts.bold, fontSize: 17, marginTop: 6 },
  card: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, padding: 14, gap: 10 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  input: { backgroundColor: colors.inset, color: colors.text, borderWidth: 1, borderColor: colors.borderStrong, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10, minHeight: 46, fontFamily: fonts.regular, fontSize: 14 },
  time: { width: 82, textAlign: 'center', fontFamily: fonts.mono },
  number: { width: 70, textAlign: 'center', fontFamily: fonts.mono },
  remove: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 6 },
  body: { color: colors.textSoft, fontFamily: fonts.regular, fontSize: 13, lineHeight: 19 },
  label: { color: colors.text, fontFamily: fonts.semibold, fontSize: 14 },
  help: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18 },
  notice: { backgroundColor: tint.brandBg, borderWidth: 1, borderColor: tint.brandBorder, borderRadius: radius.lg, padding: 14, gap: 6 },
  noticeTitle: { color: colors.brand, fontFamily: fonts.bold, fontSize: 15 },
  warn: { color: colors.warn, fontFamily: fonts.medium, fontSize: 13 },
  danger: { color: colors.danger, fontFamily: fonts.medium, fontSize: 12 },
  error: { color: colors.danger, fontFamily: fonts.medium, fontSize: 13, lineHeight: 20 },
  success: { color: colors.connected, fontFamily: fonts.semibold, fontSize: 14 },
  footer: { padding: space.lg, paddingBottom: 20, borderTopWidth: 1, borderTopColor: colors.border },
});
