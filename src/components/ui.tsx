import {
  ActivityIndicator,
  Platform,
  StatusBar,
  StyleProp,
  StyleSheet,
  Text,
  TextStyle,
  TouchableOpacity,
  View,
  ViewStyle,
} from 'react-native';

import { colors, fonts, radius } from '../theme';

const TOP_INSET = Platform.OS === 'android' ? StatusBar.currentHeight ?? 24 : 47;

/** Full-screen dark background with the status-bar inset applied. */
export function Screen({
  children,
  style,
  padTop = true,
}: {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  padTop?: boolean;
}) {
  return (
    <View
      style={[
        { flex: 1, backgroundColor: colors.bg, paddingTop: padTop ? TOP_INSET + 6 : 0 },
        style,
      ]}
    >
      {children}
    </View>
  );
}

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'disabled';

export function Button({
  label,
  onPress,
  variant = 'primary',
  icon,
  disabled,
  loading,
  height = 48,
  style,
}: {
  label: string;
  onPress?: () => void;
  variant?: ButtonVariant;
  icon?: React.ReactNode;
  disabled?: boolean;
  loading?: boolean;
  height?: number;
  style?: StyleProp<ViewStyle>;
}) {
  const isDisabled = disabled || variant === 'disabled';
  const palette: Record<ButtonVariant, { bg: string; border?: string; fg: string }> = {
    primary: { bg: colors.brand, fg: colors.onBrand },
    secondary: { bg: colors.inset, border: colors.border, fg: colors.textSoft },
    ghost: { bg: 'transparent', border: colors.borderStrong, fg: colors.textSoft },
    disabled: { bg: colors.inset, border: colors.border, fg: colors.faint },
  };
  const p = palette[isDisabled ? 'disabled' : variant];

  return (
    <TouchableOpacity
      activeOpacity={0.85}
      onPress={isDisabled ? undefined : onPress}
      disabled={isDisabled}
      style={[
        {
          height,
          borderRadius: radius.md,
          backgroundColor: p.bg,
          borderWidth: p.border ? 1 : 0,
          borderColor: p.border,
          alignItems: 'center',
          justifyContent: 'center',
          flexDirection: 'row',
        },
        style,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={p.fg} />
      ) : (
        <>
          {icon ? <View style={{ marginRight: 8 }}>{icon}</View> : null}
          <Text style={{ color: p.fg, fontFamily: fonts.semibold, fontSize: 15 }}>{label}</Text>
        </>
      )}
    </TouchableOpacity>
  );
}

/** Convenience text styles. */
export const textStyles = StyleSheet.create({
  h1: { color: colors.text, fontFamily: fonts.bold, fontSize: 26, letterSpacing: -0.3 },
  h2: { color: colors.text, fontFamily: fonts.bold, fontSize: 21, letterSpacing: -0.2 },
  body: { color: colors.text, fontFamily: fonts.medium, fontSize: 15 },
  muted: { color: colors.muted, fontFamily: fonts.regular, fontSize: 13, lineHeight: 19 },
});

export function Label({
  children,
  color = colors.muted,
  style,
}: {
  children: React.ReactNode;
  color?: string;
  style?: StyleProp<TextStyle>;
}) {
  return (
    <Text
      style={[
        {
          color,
          fontFamily: fonts.semibold,
          fontSize: 11,
          letterSpacing: 0.8,
          textTransform: 'uppercase',
        },
        style,
      ]}
    >
      {children}
    </Text>
  );
}
