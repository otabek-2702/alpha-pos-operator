import { useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';

import { Pulse, ScanLine } from '../components/anim';
import { CameraOff } from '../components/Icons';
import { Button, Screen } from '../components/ui';
import { useT } from '../i18n';
import { openAppSettings } from '../permissions';
import { colors, fonts, radius } from '../theme';

export interface PairScreenProps {
  onPaired: (url: string) => void;
}

const RETICLE = 210;
const CORNER = 36;

export function PairScreen({ onPaired }: PairScreenProps) {
  const { t } = useT();
  const [permission, requestPermission] = useCameraPermissions();
  const [error, setError] = useState<string | null>(null);
  const [scanned, setScanned] = useState('');
  const [handled, setHandled] = useState(false);

  if (!permission) {
    return (
      <Screen>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <ActivityIndicator color={colors.text} />
        </View>
      </Screen>
    );
  }

  // Camera denied/revoked (the gate normally grants it, but it can be revoked).
  if (!permission.granted) {
    return (
      <Screen>
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 28 }}>
          <View
            style={{
              width: 88,
              height: 88,
              borderRadius: 24,
              backgroundColor: colors.surface,
              borderWidth: 1,
              borderColor: colors.border,
              alignItems: 'center',
              justifyContent: 'center',
              marginBottom: 24,
            }}
          >
            <CameraOff size={42} />
          </View>
          <Text style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 20, textAlign: 'center' }}>
            {t('pair.cam.title')}
          </Text>
          <Text
            style={{
              color: colors.muted,
              fontFamily: fonts.regular,
              fontSize: 13,
              lineHeight: 20,
              textAlign: 'center',
              marginTop: 8,
            }}
          >
            {t('pair.cam.desc')}
          </Text>
        </View>
        <View style={{ padding: 16 }}>
          <Button label={t('pair.cam.grant')} onPress={requestPermission} />
          <Button
            label={t('perm.openSettings')}
            variant="ghost"
            height={44}
            style={{ marginTop: 6, borderWidth: 0 }}
            onPress={openAppSettings}
          />
        </View>
      </Screen>
    );
  }

  const onBarcodeScanned = ({ data }: { data: string }) => {
    if (handled) return;
    const value = (data ?? '').trim();
    if (!value.toLowerCase().startsWith('ws://')) {
      setScanned(value);
      setError(t('pair.invalid.title'));
      return;
    }
    setError(null);
    setHandled(true);
    onPaired(value);
  };

  const cornerColor = error ? colors.danger : colors.brand;

  return (
    <View style={{ flex: 1, backgroundColor: colors.bgDeep }}>
      <CameraView
        style={{ ...StyleSheetAbsolute }}
        facing="back"
        barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
        onBarcodeScanned={handled ? undefined : onBarcodeScanned}
      />
      {/* darkening scrim for legibility */}
      <View style={{ ...StyleSheetAbsolute, backgroundColor: 'rgba(10,13,19,0.45)' }} />

      <Screen padTop style={{ backgroundColor: 'transparent' }}>
        <View style={{ paddingHorizontal: 20, paddingTop: 12, alignItems: 'center' }}>
          <Text style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 21 }}>
            {t('pair.title')}
          </Text>
          <Text
            style={{
              color: colors.muted,
              fontFamily: fonts.regular,
              fontSize: 12.5,
              lineHeight: 19,
              textAlign: 'center',
              marginTop: 5,
            }}
          >
            {t('pair.hint')}
          </Text>
        </View>

        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <View style={{ width: RETICLE, height: RETICLE }}>
            <Corner pos="tl" color={cornerColor} />
            <Corner pos="tr" color={cornerColor} />
            <Corner pos="bl" color={cornerColor} />
            <Corner pos="br" color={cornerColor} />
            {!error ? <ScanLine color={colors.brand} travel={RETICLE - 16} /> : null}
          </View>
        </View>

        <View style={{ paddingHorizontal: 16, paddingBottom: 24 }}>
          {error ? (
            <View
              style={{
                flexDirection: 'row',
                gap: 10,
                padding: 13,
                borderRadius: 14,
                backgroundColor: 'rgba(239,106,91,0.14)',
                borderWidth: 1,
                borderColor: 'rgba(239,106,91,0.4)',
              }}
            >
              <View style={{ flex: 1 }}>
                <Text style={{ color: colors.danger, fontFamily: fonts.semibold, fontSize: 13 }}>
                  {t('pair.invalid.title')}
                </Text>
                <Text style={{ color: '#e89aa2', fontFamily: fonts.regular, fontSize: 11.5, lineHeight: 17, marginTop: 2 }}>
                  {t('pair.invalid.desc')}{' '}
                  <Text style={{ fontFamily: fonts.mono, color: colors.textSoft }}>
                    {scanned.slice(0, 40) || '—'}
                  </Text>
                </Text>
              </View>
            </View>
          ) : (
            <View style={{ alignItems: 'center' }}>
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 8,
                  paddingHorizontal: 14,
                  paddingVertical: 9,
                  borderRadius: radius.pill,
                  backgroundColor: 'rgba(10,14,20,0.6)',
                  borderWidth: 1,
                  borderColor: colors.border,
                }}
              >
                <Pulse>
                  <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: colors.brand }} />
                </Pulse>
                <Text style={{ color: colors.textSoft, fontFamily: fonts.monoMedium, fontSize: 12 }}>
                  {t('pair.searching')}
                </Text>
              </View>
            </View>
          )}
        </View>
      </Screen>
    </View>
  );
}

const StyleSheetAbsolute = {
  position: 'absolute' as const,
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
};

function Corner({ pos, color }: { pos: 'tl' | 'tr' | 'bl' | 'br'; color: string }) {
  const base = {
    position: 'absolute' as const,
    width: CORNER,
    height: CORNER,
    borderColor: color,
  };
  const map = {
    tl: { top: 0, left: 0, borderTopWidth: 3, borderLeftWidth: 3, borderTopLeftRadius: 10 },
    tr: { top: 0, right: 0, borderTopWidth: 3, borderRightWidth: 3, borderTopRightRadius: 10 },
    bl: { bottom: 0, left: 0, borderBottomWidth: 3, borderLeftWidth: 3, borderBottomLeftRadius: 10 },
    br: { bottom: 0, right: 0, borderBottomWidth: 3, borderRightWidth: 3, borderBottomRightRadius: 10 },
  };
  return <View style={{ ...base, ...map[pos] }} />;
}
