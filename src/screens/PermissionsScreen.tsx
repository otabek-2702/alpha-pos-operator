import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  ScrollView,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import { Bell, Camera, CallLog, CheckCircle, Info, Phone, Star, Warning, XCircle } from '../components/Icons';
import { Button, Label, Screen } from '../components/ui';
import { useT } from '../i18n';
import {
  checkPermissions,
  hasRequiredPermissions,
  openAppSettings,
  PermissionState,
  requestAllPermissions,
  requestBatteryExemption,
  wasBatteryPrompted,
} from '../permissions';
import { colors, fonts, radius, space, tint } from '../theme';

export interface PermissionsScreenProps {
  onReady: () => void;
}

type PermKey = keyof PermissionState;

interface RowDef {
  key: PermKey;
  Icon: (p: { size?: number; color?: string }) => JSX.Element;
  required: boolean;
}

const ROWS: RowDef[] = [
  { key: 'camera', Icon: Camera, required: true },
  { key: 'phone', Icon: Phone, required: true },
  { key: 'callLog', Icon: CallLog, required: true },
  { key: 'notifications', Icon: Bell, required: false },
];

const LABEL: Record<PermKey, { title: string; desc: string }> = {
  camera: { title: 'perm.camera', desc: 'perm.camera.desc' },
  phone: { title: 'perm.phone', desc: 'perm.phone.desc' },
  callLog: { title: 'perm.calllog', desc: 'perm.calllog.desc' },
  notifications: { title: 'perm.notif', desc: 'perm.notif.desc' },
};

export function PermissionsScreen({ onReady }: PermissionsScreenProps) {
  const { t } = useT();
  const [state, setState] = useState<PermissionState | null>(null);
  const [busy, setBusy] = useState(false);
  const [attempted, setAttempted] = useState(false);
  const [batteryDone, setBatteryDone] = useState(false);

  const refresh = useCallback(async () => setState(await checkPermissions()), []);

  useEffect(() => {
    (async () => {
      await refresh();
      setBatteryDone(await wasBatteryPrompted());
    })();
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') refresh();
    });
    return () => sub.remove();
  }, [refresh]);

  const grant = useCallback(async () => {
    setBusy(true);
    setAttempted(true);
    try {
      setState(await requestAllPermissions());
    } finally {
      setBusy(false);
    }
  }, []);

  const handleBattery = useCallback(async () => {
    await requestBatteryExemption();
    setBatteryDone(true);
  }, []);

  if (!state) {
    return (
      <Screen>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <ActivityIndicator color={colors.text} />
        </View>
      </Screen>
    );
  }

  const requiredGranted = ROWS.filter((r) => r.required && state[r.key]).length;
  const requiredTotal = ROWS.filter((r) => r.required).length;
  const ready = hasRequiredPermissions(state);
  const someDenied = attempted && ROWS.some((r) => r.required && !state[r.key]);
  const firstMissing = ROWS.find((r) => r.required && !state[r.key])?.key;

  const subtitle = ready
    ? t('perm.subtitle.ready')
    : someDenied
    ? t('perm.subtitle.blocked')
    : attempted
    ? t('perm.subtitle.partial')
    : t('perm.subtitle');

  return (
    <Screen>
      <ScrollView contentContainerStyle={{ padding: space.lg, paddingBottom: space.xl }}>
        <View style={{ flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between' }}>
          <Text style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 22 }}>
            {t('perm.title')}
          </Text>
          {attempted || ready ? (
            <Text
              style={{
                fontFamily: fonts.monoSemibold,
                fontSize: 12,
                color: ready ? colors.connected : colors.brand,
              }}
            >
              {requiredGranted} / {requiredTotal}
            </Text>
          ) : null}
        </View>
        <Text
          style={{
            color: ready ? colors.connected : colors.muted,
            fontFamily: fonts.regular,
            fontSize: 13,
            marginTop: 3,
            marginBottom: 12,
          }}
        >
          {subtitle}
        </Text>

        {attempted || ready ? (
          <View
            style={{
              height: 5,
              borderRadius: radius.pill,
              backgroundColor: colors.raised,
              overflow: 'hidden',
              marginBottom: 14,
            }}
          >
            <View
              style={{
                width: `${(requiredGranted / requiredTotal) * 100}%`,
                height: '100%',
                borderRadius: radius.pill,
                backgroundColor: ready ? colors.connected : colors.brand,
              }}
            />
          </View>
        ) : null}

        <View style={{ gap: 8 }}>
          {ROWS.map((row) => {
            const granted = state[row.key];
            const denied = row.required && attempted && !granted;
            const highlight = !granted && !denied && row.key === firstMissing;
            const iconColor = granted ? colors.connected : denied ? colors.danger : colors.muted;
            return (
              <View
                key={row.key}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 11,
                  padding: 10,
                  borderRadius: radius.md,
                  backgroundColor: denied
                    ? tint.dangerSoft
                    : highlight
                    ? tint.brandBg
                    : colors.inset,
                  borderWidth: 1,
                  borderColor: denied
                    ? tint.dangerBorder
                    : highlight
                    ? tint.brandBorder
                    : colors.border,
                }}
              >
                <View
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 10,
                    alignItems: 'center',
                    justifyContent: 'center',
                    backgroundColor: granted ? tint.connectedSoft : colors.raised,
                  }}
                >
                  <row.Icon size={18} color={iconColor} />
                </View>
                <View style={{ flex: 1, minWidth: 0 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                    <Text
                      style={{
                        color: denied ? colors.danger : colors.text,
                        fontFamily: fonts.semibold,
                        fontSize: 13.5,
                      }}
                    >
                      {t(LABEL[row.key].title as never)}
                    </Text>
                    {!granted ? (
                      <Text
                        style={{
                          fontFamily: fonts.monoSemibold,
                          fontSize: 9,
                          letterSpacing: 0.6,
                          color: row.required ? colors.danger : colors.muted2,
                          borderWidth: 1,
                          borderColor: row.required ? tint.dangerBorder : colors.borderStrong,
                          borderRadius: 5,
                          paddingHorizontal: 4,
                          paddingVertical: 1,
                          overflow: 'hidden',
                        }}
                      >
                        {row.required ? t('perm.required') : t('perm.optional')}
                      </Text>
                    ) : null}
                  </View>
                  <Text
                    style={{
                      color: denied ? '#e89aa2' : colors.muted,
                      fontFamily: fonts.regular,
                      fontSize: 11.5,
                      marginTop: 1,
                    }}
                  >
                    {denied ? t('perm.denied.row') : t(LABEL[row.key].desc as never)}
                  </Text>
                </View>
                {granted ? (
                  <CheckCircle size={22} />
                ) : denied ? (
                  <Text
                    style={{
                      fontFamily: fonts.monoSemibold,
                      fontSize: 10,
                      letterSpacing: 0.4,
                      color: colors.danger,
                      borderWidth: 1,
                      borderColor: tint.dangerBorder,
                      borderRadius: 6,
                      paddingHorizontal: 7,
                      paddingVertical: 3,
                      overflow: 'hidden',
                    }}
                  >
                    {t('perm.denied.badge')}
                  </Text>
                ) : (
                  <XCircle size={22} />
                )}
              </View>
            );
          })}
        </View>

        {/* Battery optimization */}
        {batteryDone ? (
          <View
            style={{
              marginTop: 10,
              flexDirection: 'row',
              alignItems: 'center',
              gap: 10,
              padding: 11,
              borderRadius: radius.md,
              backgroundColor: tint.connectedSoft,
              borderWidth: 1,
              borderColor: 'rgba(54,192,126,0.28)',
            }}
          >
            <Star size={18} />
            <Text style={{ flex: 1, color: '#9fdcc0', fontFamily: fonts.regular, fontSize: 11.5, lineHeight: 16 }}>
              {t('perm.battery.done')}
            </Text>
          </View>
        ) : (
          <View
            style={{
              marginTop: 10,
              flexDirection: 'row',
              gap: 11,
              padding: 11,
              borderRadius: radius.md,
              backgroundColor: tint.warnSoft,
              borderWidth: 1,
              borderColor: 'rgba(229,165,59,0.3)',
            }}
          >
            <Warning size={20} />
            <View style={{ flex: 1 }}>
              <Text style={{ color: colors.warn, fontFamily: fonts.semibold, fontSize: 12.5 }}>
                {t('perm.battery.title')}
              </Text>
              <Text style={{ color: '#cdb87a', fontFamily: fonts.regular, fontSize: 11, lineHeight: 16, marginTop: 2 }}>
                {t('perm.battery.desc')}
              </Text>
              <TouchableOpacity
                onPress={handleBattery}
                activeOpacity={0.8}
                style={{
                  marginTop: 8,
                  alignSelf: 'flex-start',
                  height: 32,
                  paddingHorizontal: 14,
                  borderRadius: radius.sm,
                  backgroundColor: 'rgba(229,165,59,0.16)',
                  borderWidth: 1,
                  borderColor: 'rgba(229,165,59,0.4)',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Text style={{ color: colors.warn, fontFamily: fonts.semibold, fontSize: 12 }}>
                  {t('perm.battery.fix')}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        )}

        {someDenied ? (
          <View
            style={{
              marginTop: 12,
              flexDirection: 'row',
              gap: 10,
              padding: 11,
              borderRadius: radius.md,
              backgroundColor: colors.inset,
              borderWidth: 1,
              borderColor: colors.borderStrong,
              borderStyle: 'dashed',
            }}
          >
            <Info size={18} />
            <Text style={{ flex: 1, color: colors.muted, fontFamily: fonts.regular, fontSize: 11, lineHeight: 17 }}>
              {t('perm.denied.hint')}
            </Text>
          </View>
        ) : null}
      </ScrollView>

      <View style={{ padding: space.lg, paddingTop: 12, borderTopWidth: 1, borderTopColor: colors.inset }}>
        {ready ? (
          <Button label={t('perm.continue')} onPress={onReady} />
        ) : someDenied ? (
          <>
            <Button label={t('perm.openSettings')} variant="secondary" onPress={openAppSettings} />
            <Button label={t('perm.continue.disabled')} variant="disabled" height={44} style={{ marginTop: 8 }} />
          </>
        ) : (
          <>
            <Button label={t('perm.grant')} onPress={grant} loading={busy} />
            <Button label={t('perm.continue')} variant="disabled" height={46} style={{ marginTop: 8 }} />
          </>
        )}
      </View>
    </Screen>
  );
}
