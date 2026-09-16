import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, BackHandler, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';

import { listFolders, recordingFolderLabel } from '../operator';
import type { FolderListing, RecordingFolder } from '../operator';
import { colors, fonts, radius, space } from '../theme';
import { Button, Screen } from './ui';

export interface FolderBrowserProps {
  onSelect: (folder: RecordingFolder) => void;
  onClose: () => void;
}

/** In-app browser over internal storage. Uses "All files access" instead of Android's folder chooser. */
export function FolderBrowser({ onSelect, onClose }: FolderBrowserProps) {
  const [listing, setListing] = useState<FolderListing | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const open = useCallback(async (path: string | null) => {
    setLoading(true);
    setError(null);
    try {
      const next = await listFolders(path);
      if (next) setListing(next);
      else setError('“Barcha fayllarga kirish” ruxsati berilmagan. Ruxsatlar bo‘limida ruxsat bering.');
    } catch (failure) {
      const message = (failure as { message?: unknown }).message;
      setError(typeof message === 'string' && message ? message : 'Papkani ochib bo‘lmadi.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { void open(null); }, [open]);

  // Registered after the app's handler, so Android back walks up folders before leaving the page.
  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (listing?.parent && !loading) void open(listing.parent);
      else if (!loading) onClose();
      return true;
    });
    return () => subscription.remove();
  }, [listing, loading, open, onClose]);

  const hint = !listing || listing.isRoot
    ? 'Qo‘ng‘iroq yozuvlari turgan papkani oching. Samsung: Recordings → Call.'
    : listing.audioCount > 0
      ? `Bu papkada ${listing.audioCount}${listing.truncated ? '+' : ''} ta audio yozuv bor.`
      : 'Bu papkada hozircha audio yozuv yo‘q.';

  return (
    <Screen>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>Papkani tanlash</Text>
        <TouchableOpacity accessibilityRole="button" onPress={onClose} style={styles.back}><Text style={styles.backText}>Yopish</Text></TouchableOpacity>
      </View>
      <Text numberOfLines={2} style={styles.path}>{listing ? recordingFolderLabel(listing) : 'Ichki xotira'}</Text>
      <ScrollView contentContainerStyle={styles.content}>
        {listing?.parent ? <Row label="‹  Yuqoriga" onPress={() => void open(listing.parent)} disabled={loading} /> : null}
        {loading && !listing ? <ActivityIndicator color={colors.brand} /> : null}
        {listing?.folders.map((child) => <Row key={child.path} label={child.name} chevron onPress={() => void open(child.path)} disabled={loading} />)}
        {listing && listing.folders.length === 0 ? <Text style={styles.help}>Ichki papkalar yo‘q.</Text> : null}
        {error ? <Text accessibilityLiveRegion="polite" style={styles.error}>{error}</Text> : null}
      </ScrollView>
      <View style={styles.footer}>
        <Text style={styles.help}>{hint}</Text>
        <Button label="Shu papkani tanlash" onPress={() => { if (listing) onSelect(listing); }} disabled={!listing || listing.isRoot || loading} />
      </View>
    </Screen>
  );
}

function Row({ label, onPress, disabled, chevron }: { label: string; onPress: () => void; disabled: boolean; chevron?: boolean }) {
  return (
    <TouchableOpacity accessibilityRole="button" onPress={onPress} disabled={disabled} style={styles.row}>
      <Text numberOfLines={1} style={styles.rowLabel}>{label}</Text>
      {chevron ? <Text style={styles.chevron}>›</Text> : null}
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: space.lg, paddingBottom: 4, gap: 8 },
  title: { color: colors.text, fontFamily: fonts.bold, fontSize: 23, flex: 1 },
  back: { minHeight: 44, justifyContent: 'center', paddingLeft: 8 },
  backText: { color: colors.brand, fontFamily: fonts.semibold, fontSize: 14 },
  path: { color: colors.textSoft, fontFamily: fonts.medium, fontSize: 13, paddingHorizontal: space.lg, paddingBottom: 8 },
  content: { padding: space.lg, paddingTop: 4, gap: 8, paddingBottom: space.xl },
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 52, paddingHorizontal: 14, borderRadius: radius.md, backgroundColor: colors.inset, borderWidth: 1, borderColor: colors.border },
  rowLabel: { flex: 1, color: colors.text, fontFamily: fonts.semibold, fontSize: 15 },
  chevron: { color: colors.muted, fontFamily: fonts.bold, fontSize: 20 },
  help: { color: colors.muted, fontFamily: fonts.regular, fontSize: 12, lineHeight: 18 },
  error: { color: colors.danger, fontFamily: fonts.medium, fontSize: 13, lineHeight: 20 },
  footer: { padding: space.lg, paddingBottom: 20, gap: 10, borderTopWidth: 1, borderTopColor: colors.border },
});
