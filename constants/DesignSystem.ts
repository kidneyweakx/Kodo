/*
 * mi-band-9-active — design system tokens
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import type { TextStyle } from 'react-native';

export const Spacing = {
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
  xxxl: 48,
} as const;

export const Radius = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  pill: 999,
} as const;

export const IconSize = {
  xs: 12,
  sm: 16,
  md: 20,
  lg: 24,
  xl: 32,
  xxl: 48,
} as const;

export const Size = {
  minTouchTarget: 44,
  buttonSm: 36,
  buttonMd: 44,
  buttonLg: 52,
  hairline: 0.5,
} as const;

export const Typography = {
  displayLarge: { fontSize: 40, lineHeight: 48, fontWeight: '700', letterSpacing: -0.5 },
  displayMedium: { fontSize: 32, lineHeight: 40, fontWeight: '700', letterSpacing: -0.4 },
  headlineLarge: { fontSize: 28, lineHeight: 34, fontWeight: '700', letterSpacing: -0.3 },
  headlineMedium: { fontSize: 22, lineHeight: 28, fontWeight: '600', letterSpacing: -0.2 },
  titleLarge: { fontSize: 18, lineHeight: 24, fontWeight: '600' },
  titleMedium: { fontSize: 16, lineHeight: 22, fontWeight: '600' },
  bodyLarge: { fontSize: 16, lineHeight: 24, fontWeight: '400' },
  bodyMedium: { fontSize: 14, lineHeight: 20, fontWeight: '400' },
  caption: { fontSize: 12, lineHeight: 16, fontWeight: '500', letterSpacing: 0.2 },
  micro: { fontSize: 10, lineHeight: 14, fontWeight: '600', letterSpacing: 0.4 },
} satisfies Record<string, TextStyle>;

export type TypographyVariant = keyof typeof Typography;

export const Shadow = {
  subtle: {
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.06,
    shadowRadius: 2,
    elevation: 1,
  },
  medium: {
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.12,
    shadowRadius: 8,
    elevation: 4,
  },
  heavy: {
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.22,
    shadowRadius: 24,
    elevation: 10,
  },
  glow: (accent: string) => ({
    shadowColor: accent,
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.45,
    shadowRadius: 20,
    elevation: 8,
  }),
} as const;

export const Motion = {
  duration: {
    micro: 120,
    fast: 180,
    base: 260,
    slow: 420,
  },
  easing: {
    standard: [0.2, 0, 0, 1] as const,
    decelerate: [0, 0, 0, 1] as const,
    accelerate: [0.3, 0, 1, 1] as const,
  },
} as const;
