/**
 * Design tokens for AlphaPOS Operator Link.
 * Mirrors the approved Claude Design system (dark, Hanken Grotesk / JetBrains Mono).
 */

export const colors = {
  bg: '#0E1219',
  bgDeep: '#0A0D13',
  surface: '#161B24',
  inset: '#11161E',
  raised: '#1B212C',
  border: '#262E3B',
  borderStrong: '#323C4C',

  brand: '#6E8BFF',
  brandDark: '#4F6BE0',
  onBrand: '#0B1020',

  connected: '#36C07E',
  warn: '#E5A53B',
  danger: '#EF6A5B',
  outgoing: '#45A8E0',

  text: '#E8EBF1',
  textSoft: '#C6CDD9',
  muted: '#9AA4B2',
  muted2: '#6B7585',
  faint: '#4B5563',
} as const;

/** Font family names registered by @expo-google-fonts (see App.tsx useFonts). */
export const fonts = {
  regular: 'HankenGrotesk_400Regular',
  medium: 'HankenGrotesk_500Medium',
  semibold: 'HankenGrotesk_600SemiBold',
  bold: 'HankenGrotesk_700Bold',
  mono: 'JetBrainsMono_400Regular',
  monoMedium: 'JetBrainsMono_500Medium',
  monoSemibold: 'JetBrainsMono_600SemiBold',
  monoBold: 'JetBrainsMono_700Bold',
} as const;

export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  pill: 999,
} as const;

export const space = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

/** Translucent fills used for tinted cards / status surfaces. */
export const tint = {
  connectedBg: 'rgba(54,192,126,0.13)',
  connectedBorder: 'rgba(54,192,126,0.32)',
  connectedSoft: 'rgba(54,192,126,0.10)',
  warnBg: 'rgba(229,165,59,0.13)',
  warnBorder: 'rgba(229,165,59,0.34)',
  warnSoft: 'rgba(229,165,59,0.10)',
  dangerBg: 'rgba(239,106,91,0.12)',
  dangerBorder: 'rgba(239,106,91,0.40)',
  dangerSoft: 'rgba(239,106,91,0.08)',
  brandBg: 'rgba(110,139,255,0.10)',
  brandBorder: 'rgba(110,139,255,0.35)',
  outgoingBg: 'rgba(69,168,224,0.12)',
} as const;
