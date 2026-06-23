import { ActivityIndicator, Text, TouchableOpacity, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';

import type { AppUpdates } from '../hooks/useAppUpdates';
import { useT } from '../i18n';
import { colors, fonts, radius, space } from '../theme';

/**
 * Top banner shown when an update is ready — either an OTA (JS) update that just
 * needs a reload, or a newer sideload APK to download + install.
 */
export function UpdateBanner({ updates }: { updates: AppUpdates }) {
  const { t } = useT();
  if (!updates.updateAvailable) return null;

  const isApk = !!updates.apk;
  const downloading = updates.apkDownloading;
  const title = updates.apk?.mandatory ? t('update.mandatory') : t('update.title');
  const desc = isApk
    ? `${t('update.apk.desc')} · ${updates.apk?.versionName ?? ''}`.trim()
    : t('update.ota.desc');
  const onPress = isApk ? updates.installApk : updates.applyOta;

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
          <Text style={{ color: colors.brand, fontFamily: fonts.semibold, fontSize: 13 }}>{title}</Text>
          <Text
            numberOfLines={1}
            style={{ color: colors.muted, fontFamily: fonts.regular, fontSize: 12, marginTop: 2 }}
          >
            {downloading ? t('update.downloading') : desc}
          </Text>
        </View>
        <TouchableOpacity
          onPress={onPress}
          disabled={downloading}
          activeOpacity={0.85}
          style={{
            height: 36,
            paddingHorizontal: 16,
            borderRadius: radius.sm,
            backgroundColor: colors.brand,
            alignItems: 'center',
            justifyContent: 'center',
            opacity: downloading ? 0.7 : 1,
          }}
        >
          {downloading ? (
            <ActivityIndicator color={colors.onBrand} size="small" />
          ) : (
            <Text style={{ color: colors.onBrand, fontFamily: fonts.semibold, fontSize: 13 }}>
              {t('update.button')}
            </Text>
          )}
        </TouchableOpacity>
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
          <View
            style={{
              width: `${Math.round(updates.apkProgress * 100)}%`,
              height: '100%',
              backgroundColor: colors.brand,
            }}
          />
        </View>
      ) : null}
    </View>
  );
}
