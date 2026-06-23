import { useState } from 'react';
import { ActivityIndicator, Linking, ScrollView, Text, TouchableOpacity, View } from 'react-native';

import { Info, Phone, Send } from '../components/Icons';
import { Button, Label, Screen } from '../components/ui';
import { SUPPORT } from '../config';
import type { AppUpdates } from '../hooks/useAppUpdates';
import { useT } from '../i18n';
import { colors, fonts, radius, space, tint } from '../theme';

/**
 * Support & info screen: app version, support contacts (tap to open dialer /
 * Telegram / mail), and a manual update check (OTA + APK).
 */
export function SupportScreen({
  updates,
  onClose,
}: {
  updates: AppUpdates;
  onClose: () => void;
}) {
  const { t } = useT();
  const [checked, setChecked] = useState(false);

  const open = (url: string) => Linking.openURL(url).catch(() => {});
  const phoneDigits = SUPPORT.phone.replace(/[^\d+]/g, '');

  const runCheck = async () => {
    setChecked(false);
    await Promise.all([updates.checkOta(), updates.checkApk()]);
    setChecked(true);
  };

  const checking = updates.otaChecking || updates.apkChecking;

  return (
    <Screen>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          paddingHorizontal: space.lg,
          paddingVertical: 12,
        }}
      >
        <Text style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 20 }}>
          {t('support.title')}
        </Text>
        <TouchableOpacity onPress={onClose} activeOpacity={0.7} hitSlop={10}>
          <Text style={{ color: colors.brand, fontFamily: fonts.semibold, fontSize: 15 }}>
            {t('support.close')}
          </Text>
        </TouchableOpacity>
      </View>

      <ScrollView contentContainerStyle={{ padding: space.lg, paddingTop: 4, gap: 18 }}>
        {/* Version */}
        <View
          style={{
            backgroundColor: colors.surface,
            borderRadius: radius.lg,
            borderWidth: 1,
            borderColor: colors.border,
            padding: 16,
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <View>
            <Label>{t('support.version')}</Label>
            <Text style={{ color: colors.text, fontFamily: fonts.monoSemibold, fontSize: 16, marginTop: 4 }}>
              {updates.currentVersionName} ({updates.currentVersionCode})
            </Text>
          </View>
          <Info size={22} color={colors.muted} />
        </View>

        {/* Updates */}
        <View>
          <Label style={{ marginBottom: 8, marginLeft: 2 }}>{t('support.updates')}</Label>
          <View
            style={{
              backgroundColor: colors.surface,
              borderRadius: radius.lg,
              borderWidth: 1,
              borderColor: colors.border,
              padding: 16,
            }}
          >
            <Button
              label={checking ? t('support.checking') : t('support.checkUpdate')}
              onPress={runCheck}
              loading={checking}
              height={46}
            />
            {checked && !updates.updateAvailable && !checking ? (
              <View
                style={{
                  marginTop: 12,
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 8,
                  paddingHorizontal: 12,
                  paddingVertical: 10,
                  borderRadius: radius.md,
                  backgroundColor: tint.connectedSoft,
                  borderWidth: 1,
                  borderColor: 'rgba(54,192,126,0.28)',
                }}
              >
                <Text style={{ color: '#9fdcc0', fontFamily: fonts.medium, fontSize: 13 }}>
                  {t('support.upToDate')}
                </Text>
              </View>
            ) : null}
          </View>
        </View>

        {/* Contacts */}
        <View>
          <Label style={{ marginBottom: 8, marginLeft: 2 }}>{t('support.contact')}</Label>
          <View
            style={{
              backgroundColor: colors.surface,
              borderRadius: radius.lg,
              borderWidth: 1,
              borderColor: colors.border,
              overflow: 'hidden',
            }}
          >
            <ContactRow
              icon={<Phone size={18} color={colors.brand} />}
              label={t('support.phone')}
              value={SUPPORT.phone}
              onPress={() => open(`tel:${phoneDigits}`)}
            />
            <Divider />
            <ContactRow
              icon={<Send size={17} color={colors.brand} />}
              label={t('support.telegram')}
              value={`@${SUPPORT.telegram}`}
              onPress={() => open(`https://t.me/${SUPPORT.telegram}`)}
            />
            <Divider />
            <ContactRow
              icon={<Info size={18} color={colors.brand} />}
              label={t('support.email')}
              value={SUPPORT.email}
              onPress={() => open(`mailto:${SUPPORT.email}`)}
            />
          </View>
        </View>
      </ScrollView>
    </Screen>
  );
}

function ContactRow({
  icon,
  label,
  value,
  onPress,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  onPress: () => void;
}) {
  return (
    <TouchableOpacity
      onPress={onPress}
      activeOpacity={0.7}
      style={{ flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14 }}
    >
      <View
        style={{
          width: 36,
          height: 36,
          borderRadius: 10,
          backgroundColor: tint.brandBg,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {icon}
      </View>
      <View style={{ flex: 1, minWidth: 0 }}>
        <Text style={{ color: colors.muted, fontFamily: fonts.regular, fontSize: 12 }}>{label}</Text>
        <Text style={{ color: colors.text, fontFamily: fonts.medium, fontSize: 15, marginTop: 1 }}>
          {value}
        </Text>
      </View>
    </TouchableOpacity>
  );
}

function Divider() {
  return <View style={{ height: 1, backgroundColor: colors.border, marginLeft: 62 }} />;
}
