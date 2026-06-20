import { Text, TouchableOpacity, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';

import { Dots, ExpandingRing } from '../components/anim';
import { PhoneFill } from '../components/Icons';
import { LANG_LABEL, LANGS, useT } from '../i18n';
import { colors, fonts, radius } from '../theme';
import { Screen } from '../components/ui';

/**
 * Branded loading screen shown while fonts and saved state load. Lets the
 * operator pick a language before pairing.
 */
export function SplashScreen() {
  const { t, lang, setLang } = useT();

  return (
    <Screen padTop={false}>
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <View
          style={{
            width: 96,
            height: 96,
            alignItems: 'center',
            justifyContent: 'center',
            marginBottom: 26,
          }}
        >
          <ExpandingRing color="rgba(110,139,255,0.5)" borderRadius={26} duration={2400} />
          <ExpandingRing color="rgba(110,139,255,0.5)" borderRadius={26} duration={2400} delay={1200} />
          <LinearGradient
            colors={[colors.brand, colors.brandDark]}
            start={{ x: 0, y: 0 }}
            end={{ x: 1, y: 1 }}
            style={{
              width: 84,
              height: 84,
              borderRadius: 23,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <PhoneFill size={42} />
          </LinearGradient>
        </View>

        <Text style={{ color: colors.text, fontFamily: fonts.bold, fontSize: 24 }}>AlphaPOS</Text>
        <Text style={{ color: colors.brand, fontFamily: fonts.semibold, fontSize: 14, marginTop: 2 }}>
          {t('app.name')}
        </Text>
        <Text style={{ color: colors.muted, fontFamily: fonts.regular, fontSize: 12, marginTop: 8 }}>
          {t('app.tagline')}
        </Text>

        <View
          style={{
            flexDirection: 'row',
            marginTop: 22,
            padding: 4,
            borderRadius: radius.pill,
            backgroundColor: colors.inset,
            borderWidth: 1,
            borderColor: colors.border,
          }}
        >
          {LANGS.map((l) => {
            const active = l === lang;
            return (
              <TouchableOpacity
                key={l}
                onPress={() => setLang(l)}
                activeOpacity={0.8}
                style={{
                  height: 30,
                  paddingHorizontal: 15,
                  borderRadius: radius.pill,
                  alignItems: 'center',
                  justifyContent: 'center',
                  backgroundColor: active ? colors.brand : 'transparent',
                }}
              >
                <Text
                  style={{
                    color: active ? colors.onBrand : colors.muted,
                    fontFamily: active ? fonts.semibold : fonts.medium,
                    fontSize: 12,
                  }}
                >
                  {LANG_LABEL[l]}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>
      </View>

      <View style={{ alignItems: 'center', paddingBottom: 44 }}>
        <Dots color={colors.brand} />
        <Text
          style={{
            color: colors.muted2,
            fontFamily: fonts.monoMedium,
            fontSize: 12,
            marginTop: 12,
          }}
        >
          {t('splash.loading')}
        </Text>
      </View>
    </Screen>
  );
}
