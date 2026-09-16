import { ActivityIndicator, Text, TouchableOpacity, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';

import type { AppUpdates } from '../hooks/useAppUpdates';
import { describeUpdate } from '../hooks/useAppUpdates';
import { useT } from '../i18n';
import { colors, fonts, radius, space } from '../theme';

/**
 * Top banner while a newer version is on its way. Installation is automatic;
 * the button appears only when Android needs a confirmation or a retry helps.
 */
export function UpdateBanner({ updates }: { updates: AppUpdates }) {
  const { t } = useT();
  const status = updates.status;
  if (!status || !updates.updateAvailable) return null;

  const needsAction = status.state === 'waiting_user' || status.state === 'error';
  const busy = updates.checking || status.state === 'installing';
  const downloading = status.state === 'downloading';
  const progress = status.size > 0 ? Math.min(1, status.downloaded / status.size) : 0;

  return (
    <View style={{ paddingHorizontal: space.lg, paddingTop: 8 }}>
      <LinearGradient
        colors={['rgba(110,139,255,0.18)', colors.inset]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={{
          borderRadius: 14,
          borderWidth: 1,
          borderColor: 'rgba(110,139,255,0.4)',
          padding: 12,
          flexDirection: 'row',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={{ color: colors.brand, fontFamily: fonts.semibold, fontSize: 13 }}>
            {t('update.title')} · {status.latestName}
          </Text>
          <Text numberOfLines={2} style={{ color: colors.muted, fontFamily: fonts.regular, fontSize: 12, marginTop: 2 }}>
            {describeUpdate(status)}
          </Text>
        </View>
        {needsAction ? (
          <TouchableOpacity
            onPress={() => void updates.checkNow()}
            disabled={busy}
            activeOpacity={0.85}
            style={{
              height: 36,
              paddingHorizontal: 16,
              borderRadius: radius.sm,
              backgroundColor: colors.brand,
              alignItems: 'center',
              justifyContent: 'center',
              opacity: busy ? 0.7 : 1,
            }}
          >
            {busy ? (
              <ActivityIndicator color={colors.onBrand} size="small" />
            ) : (
              <Text style={{ color: colors.onBrand, fontFamily: fonts.semibold, fontSize: 13 }}>
                {t('update.button')}
              </Text>
            )}
          </TouchableOpacity>
        ) : busy ? <ActivityIndicator color={colors.brand} size="small" /> : null}
      </LinearGradient>

      {downloading ? (
        <View
          style={{
            height: 4,
            borderRadius: radius.pill,
            backgroundColor: colors.raised,
            marginTop: 6,
            overflow: 'hidden',
          }}
        >
          <View style={{ width: `${Math.round(progress * 100)}%`, height: '100%', backgroundColor: colors.brand }} />
        </View>
      ) : null}
    </View>
  );
}
