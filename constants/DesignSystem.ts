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
  hero: { fontSize: 64, lineHeight: 68, fontWeight: '800', letterSpacing: -1.5 },
  displayLarge: { fontSize: 48, lineHeight: 52, fontWeight: '700', letterSpacing: -0.8 },
  displayMedium: { fontSize: 36, lineHeight: 42, fontWeight: '700', letterSpacing: -0.5 },
  headlineLarge: { fontSize: 28, lineHeight: 34, fontWeight: '700', letterSpacing: -0.3 },
  headlineMedium: { fontSize: 22, lineHeight: 28, fontWeight: '600', letterSpacing: -0.2 },
  titleLarge: { fontSize: 18, lineHeight: 24, fontWeight: '600' },
  titleMedium: { fontSize: 16, lineHeight: 22, fontWeight: '600' },
  bodyLarge: { fontSize: 16, lineHeight: 24, fontWeight: '400' },
  bodyMedium: { fontSize: 14, lineHeight: 20, fontWeight: '400' },
  caption: { fontSize: 12, lineHeight: 16, fontWeight: '500', letterSpacing: 0.6 },
  micro: { fontSize: 10, lineHeight: 14, fontWeight: '600', letterSpacing: 0.8 },
} satisfies Record<string, TextStyle>;

/** Apply to numeric heroes so columns line up. */
export const tabularNums = { fontVariant: ['tabular-nums' as const] };

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
    ambient: 6000,
  },
  easing: {
    /** Linear's signature "Expo.out" — feels premium on mobile. */
    expoOut: [0.16, 1, 0.3, 1] as const,
    standard: [0.2, 0, 0, 1] as const,
    decelerate: [0, 0, 0, 1] as const,
    accelerate: [0.3, 0, 1, 1] as const,
  },
  spring: {
    /** Press feedback. */
    press: { stiffness: 320, damping: 22, mass: 0.7 },
    /** Sheet/modal entry. */
    sheet: { stiffness: 90, damping: 20, mass: 1 },
  },
} as const;
