import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

import { Clock } from '../components/Icons';
import { Screen } from '../components/ui';
import type { RuntimePeriod } from '../operator';
import { colors, fonts, radius, space } from '../theme';

export function WorkHistoryScreen({ periods, onClose }: { periods: RuntimePeriod[]; onClose: () => void }) {
  const sorted = [...periods].sort((a, b) => b.startedAt - a.startedAt);
  return (
    <Screen>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>Ishlash tarixi</Text>
        <TouchableOpacity accessibilityRole="button" accessibilityLabel="Orqaga" onPress={onClose} style={styles.back}><Text style={styles.backText}>Orqaga</Text></TouchableOpacity>
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.description}>Xizmatning telefonda ishlagan davrlari. Bu POS bilan ulanish tarixi emas. Telefon to‘satdan o‘chsa, to‘xtash vaqti oxirgi qayd bo‘yicha taxminiy ko‘rsatiladi.</Text>
        {sorted.length === 0 ? <View style={styles.empty}><Clock size={28} /><Text style={styles.emptyTitle}>Hali tarix yo‘q</Text><Text style={styles.description}>Xizmat ishga tushgach, ishlash davrlari shu yerda saqlanadi.</Text></View> : sorted.map((period, index) => {
          const laterPeriod = sorted[index - 1];
          const gap = period.endedAt && laterPeriod ? laterPeriod.startedAt - period.endedAt : 0;
          return (
            <View key={`${period.startedAt}-${index}`} style={{ gap: 10 }}>
              {gap > 1000 ? <View style={styles.gap}><Text style={styles.gapText}>Tanaffus: {duration(gap)}{period.approximate ? ' · taxminan' : ''}</Text></View> : null}
              <View style={styles.card}>
                <View style={styles.cardHeader}><View style={[styles.dot, { backgroundColor: period.endedAt ? colors.muted : colors.connected }]} /><Text style={[styles.cardTitle, { color: period.endedAt ? colors.text : colors.connected }]}>{period.endedAt ? 'Xizmat ishlagan' : 'Hozir ishlayapti'}</Text></View>
                <View style={styles.row}><Text style={styles.description}>Boshlangan</Text><Text style={styles.time}>{formatDate(period.startedAt)}</Text></View>
                <View style={styles.row}><Text style={styles.description}>To‘xtagan</Text><Text style={styles.time}>{period.endedAt ? formatDate(period.endedAt) : 'Davom etmoqda'}</Text></View>
                {period.endedAt ? <Text style={styles.duration}>Davomiyligi: {duration(period.endedAt - period.startedAt)}{period.approximate ? ' · taxminan' : ''}</Text> : null}
                {period.approximate ? <Text style={styles.approximate}>Aniq to‘xtash vaqti olinmagan. Oxirgi faol qayd ishlatildi.</Text> : null}
                {period.endReason && !period.approximate ? <Text style={styles.description}>{reasonLabel(period.endReason)}</Text> : null}
              </View>
            </View>
          );
        })}
      </ScrollView>
    </Screen>
  );
}

function formatDate(at: number) {
  const date = new Date(at);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getDate())}.${pad(date.getMonth() + 1)}.${date.getFullYear()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function duration(ms: number) {
  const minutes = Math.max(0, Math.floor(ms / 60000));
  if (minutes < 1) return '1 daqiqadan kam';
  const hours = Math.floor(minutes / 60);
  return hours ? `${hours} soat ${minutes % 60} daqiqa` : `${minutes} daqiqa`;
}

function reasonLabel(reason: string) {
  if (/permission/i.test(reason)) return 'Kerakli ruxsat olib tashlangan.';
  if (/shutdown|reboot/i.test(reason)) return 'Telefon o‘chirilgan yoki qayta yoqilgan.';
  if (/disabled|manual/i.test(reason)) return 'Xizmat o‘chirilgan.';
  return 'Xizmat to‘xtagan.';
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 8 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 24 },
  back: { minHeight: 44, paddingLeft: 12, justifyContent: 'center' },
  backText: { color: colors.brand, fontFamily: fonts.semibold, fontSize: 14 },
  content: { padding: space.lg, gap: 12, paddingBottom: space.xl },
  description: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18 },
  empty: { marginTop: 24, alignItems: 'center', gap: 12, padding: 24 },
  emptyTitle: { color: colors.text, fontFamily: fonts.semibold, fontSize: 17 },
  card: { backgroundColor: colors.surface, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border, padding: 16, gap: 9 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  cardTitle: { fontFamily: fonts.semibold, fontSize: 15 },
  dot: { width: 7, height: 7, borderRadius: 4 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 6 },
  time: { color: colors.textSoft, fontFamily: fonts.mono, fontSize: 11 },
  duration: { color: colors.textSoft, fontFamily: fonts.medium, fontSize: 12, marginTop: 4 },
  approximate: { color: colors.warn, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18 },
  gap: { paddingHorizontal: 16, paddingVertical: 5 },
  gapText: { color: colors.muted2, fontFamily: fonts.medium, fontSize: 12 },
});
