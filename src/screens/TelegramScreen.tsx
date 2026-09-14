import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Linking, Platform, ScrollView, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';

import { Button, Screen } from '../components/ui';
import type { RuntimeSnapshot, TelegramSettings } from '../operator';
import { colors, fonts, radius, space, tint } from '../theme';

export interface TelegramScreenProps {
  config: TelegramSettings;
  onSave: (settings: TelegramSettings) => Promise<void>;
  onPickFolder: () => Promise<{ uri: string; name: string } | null>;
  onClose: () => void;
  onScanSetup?: () => void;
  snapshot: RuntimeSnapshot['telegram'] | null;
}

export function TelegramScreen({ config, onSave, onPickFolder, onClose, onScanSetup, snapshot }: TelegramScreenProps) {
  const [enabled, setEnabled] = useState(config.enabled);
  const [botToken, setBotToken] = useState('');
  const [chatId, setChatId] = useState(config.chatId);
  const [sendCallStats, setSendCallStats] = useState(config.sendCallStats === true);
  const [statsChatId, setStatsChatId] = useState(config.statsChatId ?? '');
  const [folder, setFolder] = useState({ uri: config.folderUri, name: config.folderName });
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [saved, setSaved] = useState(false);
  const [manual, setManual] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tokenStored, setTokenStored] = useState(snapshot?.hasToken ?? !!snapshot?.configured);
  const changed = () => { setSaved(false); setError(null); };

  useEffect(() => {
    if (snapshot?.hasToken || snapshot?.configured) setTokenStored(true);
  }, [snapshot?.hasToken, snapshot?.configured]);

  const pick = async () => {
    setPicking(true);
    setError(null);
    try {
      const selected = await onPickFolder();
      if (selected) { setFolder(selected); changed(); }
    } catch {
      setError('Papkani ochib bo‘lmadi. Ruxsatlar bo‘limida fayllarga kirishni tekshiring.');
    } finally { setPicking(false); }
  };

  const save = async () => {
    const token = botToken.trim();
    const group = chatId.trim();
    const statsGroup = statsChatId.trim();
    if (token && !/^\d+:[A-Za-z0-9_-]{20,}$/.test(token)) {
      setError('Bot tokenini to‘liq kiriting. Uni @BotFather orqali olasiz.');
      return;
    }
    if (group && !/^-\d+$/.test(group)) {
      setError('Ovoz yozuvlari guruhi ID si manfiy son bo‘ladi, masalan: -1001234567890.');
      return;
    }
    if (statsGroup && !/^-\d+$/.test(statsGroup)) {
      setError('Qo‘ng‘iroqlar hisoboti guruhi ID si manfiy son bo‘lishi kerak.');
      return;
    }
    if ((enabled || sendCallStats) && !token && !tokenStored) {
      setError('Telegramga yuborishni yoqish uchun bot tokenini kiriting.');
      return;
    }
    if (enabled && (!group || !folder.uri)) {
      setError('Ovoz yozuvlarini yuborish uchun guruh ID si va yozuvlar papkasi kerak.');
      return;
    }
    if (sendCallStats && !statsGroup) {
      setError('Qo‘ng‘iroqlar hisoboti uchun alohida guruh ID sini kiriting.');
      return;
    }
    if (enabled && sendCallStats && group === statsGroup) {
      setError('Ovoz yozuvlari va qo‘ng‘iroqlar hisoboti uchun alohida guruhlarni tanlang.');
      return;
    }
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await onSave({ enabled, chatId: group, statsChatId: statsGroup, sendCallStats, folderUri: folder.uri, folderName: folder.name, sendMissedCalls: false, ...(token ? { botToken: token } : {}) });
      if (token) setTokenStored(true);
      setBotToken('');
      setSaved(true);
    } catch {
      setError('Sozlamalarni saqlab bo‘lmadi. Bot, guruh va papka ma’lumotlarini tekshiring.');
    } finally { setBusy(false); }
  };

  return (
    <Screen>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>Telegram</Text>
        <TouchableOpacity accessibilityRole="button" accessibilityLabel="Orqaga" onPress={onClose} disabled={busy} style={styles.back}><Text style={styles.backText}>Orqaga</Text></TouchableOpacity>
      </View>
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
        <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
          {onScanSetup ? <Button label="Telegram QR kodini skanerlash" variant="secondary" onPress={onScanSetup} disabled={busy || picking} /> : null}
          <View style={styles.card}>
            <Text style={styles.label}>{tokenStored ? 'Telegram sozlangan' : 'Telegramni ulash'}</Text>
            <Text style={styles.body}>Ovoz yozuvlari: Smart Food ovoz yozuvlari</Text>
            <Text style={styles.body}>Hisobotlar: Smart Food qo‘ng‘iroqlar ma’lumotlari</Text>
            <Text style={styles.help}>{tokenStored ? 'Yozuvlar papkasini tanlang va kerakli yuborish turlarini yoqing.' : 'Tayyorlangan Telegram QR kodini bir marta skanerlang. Keyin Samsung yozuvlarni saqlaydigan papkani tanlang.'}</Text>
            <TouchableOpacity accessibilityRole="button" onPress={() => setManual(!manual)} disabled={busy} style={styles.linkButton}><Text style={styles.backText}>{manual ? 'Qo‘lda sozlashni yopish' : 'Qo‘lda sozlash'}</Text></TouchableOpacity>
          </View>
          <View style={styles.notice}>
            <Text style={styles.noticeTitle}>Faqat yangi ovoz yozuvlari</Text>
            <Text style={styles.body}>Birinchi sozlash vaqtida papkada bor yozuvlar yuborilmaydi. Faqat sozlangandan keyin yaratilgan yangi qo‘ng‘iroq yozuvlari yuboriladi.</Text>
          </View>
          <View style={styles.toggleRow}>
            <View style={{ flex: 1 }}><Text style={styles.label}>Ovoz yozuvlarini yuborish</Text><Text style={styles.help}>Yangi audio yozuvlarni guruhga yuborish</Text></View>
            <Switch accessibilityLabel="Telegramga ovoz yozuvlarini yuborish" value={enabled} disabled={busy} onValueChange={(value) => { setEnabled(value); changed(); }} trackColor={{ false: colors.borderStrong, true: colors.brandDark }} thumbColor={enabled ? colors.brand : colors.muted} />
          </View>

          {manual ? <View style={styles.field}>
            <Text style={styles.label}>Bot tokeni</Text>
            <TextInput accessibilityLabel="Telegram bot tokeni" value={botToken} onChangeText={(value) => { setBotToken(value); changed(); }} placeholder={tokenStored ? 'Token saqlangan · almashtirish uchun kiriting' : '@BotFather bergan token'} placeholderTextColor={colors.muted2} secureTextEntry autoCapitalize="none" autoCorrect={false} editable={!busy} style={styles.input} />
            <Text style={styles.help}>{tokenStored ? 'Bo‘sh qoldirsangiz, saqlangan token ishlatiladi.' : 'Token faqat shu telefonda himoyalangan holda saqlanadi.'}</Text>
          </View> : null}

          {manual ? <View style={styles.field}>
            <Text style={styles.label}>Ovoz yozuvlari guruhi</Text>
            <TextInput accessibilityLabel="Ovoz yozuvlari guruhi ID si" value={chatId} onChangeText={(value) => { setChatId(value); changed(); }} placeholder="Guruh ID si, masalan: -1001234567890" placeholderTextColor={colors.muted2} autoCapitalize="none" autoCorrect={false} editable={!busy} maxLength={24} style={styles.input} />
            <Text style={styles.help}>Bot guruhga qo‘shilgan va fayl yuborishga ruxsati bor bo‘lishi kerak.</Text>
          </View> : null}

          <View style={styles.field}>
            <Text style={styles.label}>Qo‘ng‘iroq yozuvlari papkasi</Text>
            <View style={styles.folder}><Text numberOfLines={3} style={[styles.body, { color: folder.uri ? colors.text : colors.muted }]}>{folder.uri ? folder.name || 'Tanlangan papka' : 'Hali papka tanlanmagan'}</Text></View>
            <Button label={folder.uri ? 'Boshqa papkani tanlash' : 'Yozuvlar papkasini tanlash'} variant="secondary" onPress={() => void pick()} loading={picking} disabled={busy || picking} />
            <Text style={styles.help}>Samsung telefon saqlaydigan audio yozuvlar papkasini tanlang. Papka o‘zgarsa, undagi avvalgi yozuvlar yuborilmaydi.</Text>
          </View>

          <View style={styles.card}>
            <View style={styles.toggleRow}>
              <View style={{ flex: 1 }}><Text style={styles.label}>Qo‘ng‘iroqlar hisoboti</Text><Text style={styles.help}>Qo‘ng‘iroq tafsilotlari va javobsiz qo‘ng‘iroqlarni alohida guruhga yuborish</Text></View>
              <Switch accessibilityLabel="Telegramga qo‘ng‘iroqlar hisobotini yuborish" value={sendCallStats} disabled={busy} onValueChange={(value) => { setSendCallStats(value); changed(); }} trackColor={{ false: colors.borderStrong, true: colors.brandDark }} thumbColor={sendCallStats ? colors.brand : colors.muted} />
            </View>
            {manual ? <>
            <Text style={[styles.label, { marginTop: 10 }]}>Qo‘ng‘iroqlar hisoboti guruhi</Text>
            <TextInput accessibilityLabel="Qo‘ng‘iroqlar hisoboti guruhi ID si" value={statsChatId} onChangeText={(value) => { setStatsChatId(value); changed(); }} placeholder="Hisobot uchun alohida guruh ID si" placeholderTextColor={colors.muted2} autoCapitalize="none" autoCorrect={false} editable={!busy} maxLength={24} style={styles.input} />
            <Text style={styles.help}>Shu botni hisobot guruhiga ham qo‘shing. Ovoz yozuvlarini o‘chirib, faqat hisobot yuborishni yoqish mumkin.</Text>
            </> : null}
            <Text style={styles.runtimeStatus}>{snapshot?.sendCallStats ? 'Hisobot yuborish yoqilgan' : 'Hisobot yuborish o‘chirilgan'}</Text>
            {snapshot?.sendCallStats ? <Text style={styles.help}>Navbatdagi hisobotlar: {snapshot.statsPending ?? 0}</Text> : null}
            {snapshot?.statsError ? <Text accessibilityLiveRegion="polite" style={styles.error}>{snapshot.statsError}</Text> : null}
            {snapshot?.statsLastSentAt ? <Text style={styles.help}>Oxirgi hisobot: {formatDate(snapshot.statsLastSentAt)}</Text> : null}
          </View>

          <View style={styles.card}>
            <Text style={styles.label}>Ovoz yozuvlarini yuborish holati</Text>
            <Text style={[styles.runtimeStatus, { color: snapshot?.enabled && snapshot.configured ? colors.connected : colors.muted }]}>{!snapshot?.configured ? 'Sozlash tugallanmagan' : snapshot.enabled ? 'Yoqilgan' : 'O‘chirilgan'}</Text>
            <View style={styles.stats}>
              <View style={{ flex: 1 }}><Text style={styles.statNumber}>{snapshot?.sent ?? 0}</Text><Text style={styles.help}>Yuborilgan</Text></View>
              <View style={{ flex: 1 }}><Text style={styles.statNumber}>{snapshot?.pending ?? 0}</Text><Text style={styles.help}>Navbatda</Text></View>
            </View>
            {snapshot?.lastError ? <Text style={styles.error}>{snapshot.lastError}</Text> : null}
            {snapshot?.lastSentAt ? <Text style={styles.help}>Oxirgi yuborish: {formatDate(snapshot.lastSentAt)}</Text> : null}
          </View>

          {manual ? <View style={styles.instructions}>
            <Text style={styles.label}>Bir martalik Telegram sozlamalari</Text>
            <Text style={styles.body}>1. @BotFather orqali “Smart POS Operator” nomli bot yarating va tokenni yuqoriga kiriting.</Text>
            <Text style={styles.body}>2. “Smart Food ovoz yozuvlari” guruhini yarating va botni unga qo‘shing.</Text>
            <Text style={styles.body}>3. Qo‘ng‘iroqlar hisoboti uchun alohida guruh yarating va shu botni unga ham qo‘shing.</Text>
            <Text style={styles.body}>4. Guruh ID larini kiriting, yozuvlar papkasini tanlang va kerakli yuborish turlarini yoqib saqlang.</Text>
            <TouchableOpacity accessibilityRole="link" onPress={() => { void Linking.openURL('https://t.me/BotFather').catch(() => setError('Telegramni ochib bo‘lmadi. @BotFather ni Telegram ichidan toping.')); }} style={styles.linkButton}><Text style={styles.backText}>Telegramda @BotFather ni ochish ↗</Text></TouchableOpacity>
          </View> : null}
          {error ? <Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}
          {saved ? <Text accessibilityLiveRegion="polite" style={styles.success}>Sozlamalar saqlandi.</Text> : null}
        </ScrollView>
        <View style={styles.footer}><Button label={saved ? 'Saqlandi' : 'Sozlamalarni saqlash'} onPress={() => void save()} loading={busy} disabled={busy || picking || saved} /></View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

function formatDate(at: number) {
  const date = new Date(at);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getDate())}.${pad(date.getMonth() + 1)}.${date.getFullYear()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 6, gap: 8 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 23, flex: 1 },
  back: { minHeight: 44, justifyContent: 'center', paddingLeft: 8 },
  backText: { color: colors.brand, fontFamily: fonts.semibold, fontSize: 14 },
  content: { padding: space.lg, gap: 20, paddingBottom: space.xl },
  notice: { backgroundColor: tint.brandBg, borderWidth: 1, borderColor: tint.brandBorder, borderRadius: radius.lg, padding: 15, gap: 7 },
  noticeTitle: { color: colors.brand, fontFamily: fonts.bold, fontSize: 16 },
  body: { color: colors.textSoft, fontFamily: fonts.regular, fontSize: 13, lineHeight: 20 },
  label: { color: colors.text, fontFamily: fonts.semibold, fontSize: 15 },
  help: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18, marginTop: 4 },
  toggleRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  field: { gap: 8 },
  input: { backgroundColor: colors.inset, color: colors.text, borderWidth: 1, borderColor: colors.borderStrong, borderRadius: radius.md, paddingHorizontal: 13, paddingVertical: 12, minHeight: 48, fontFamily: fonts.regular, fontSize: 14 },
  folder: { backgroundColor: colors.inset, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: 13 },
  card: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg, padding: 16, gap: 8 },
  runtimeStatus: { color: colors.text, fontFamily: fonts.semibold, fontSize: 13 },
  stats: { flexDirection: 'row', gap: 12, marginTop: 8 },
  statNumber: { color: colors.text, fontFamily: fonts.bold, fontSize: 28 },
  instructions: { gap: 10 },
  linkButton: { minHeight: 44, justifyContent: 'center' },
  error: { color: colors.danger, fontFamily: fonts.medium, fontSize: 13, lineHeight: 20 },
  success: { color: colors.connected, fontFamily: fonts.semibold, fontSize: 14 },
  footer: { padding: space.lg, paddingBottom: 20, borderTopWidth: 1, borderTopColor: colors.border },
});
