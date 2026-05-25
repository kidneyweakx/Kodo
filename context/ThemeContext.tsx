/*
 * mi-band-9-active — single source of truth for theme palette
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Appearance } from 'react-native';

export type ThemeMode = 'light' | 'dark' | 'auto';
export type TintIntensity = 'subtle' | 'balanced' | 'vivid';
export type ThemeId = 'aurora' | 'graphite' | 'ember' | 'lagoon';

export interface ThemePalette {
  readonly accent: string;
  readonly accentSoft: string;
  readonly accentStrong: string;
  readonly secondary: string;
  readonly background: {
    readonly primary: string;
    readonly secondary: string;
    readonly tertiary: string;
  };
  readonly text: {
    readonly primary: string;
    readonly secondary: string;
    readonly tertiary: string;
  };
  readonly border: string;
  readonly glassBorder: string;
  readonly success: string;
  readonly warning: string;
  readonly danger: string;
  readonly gradient: readonly [string, string];
}

interface ThemeBundle {
  readonly light: ThemePalette;
  readonly dark: ThemePalette;
}

// Brand-source palettes. Allowed hex literals (see CLAUDE.md rule 4).
const THEMES: Record<ThemeId, ThemeBundle> = {
  aurora: {
    dark: {
      accent: '#7CF5C4',
      accentSoft: '#1B3A2F',
      accentStrong: '#2EE0A2',
      secondary: '#8AB4FF',
      background: { primary: '#06080B', secondary: '#0F1318', tertiary: '#171C24' },
      text: { primary: '#F5F7FA', secondary: '#A8B0BE', tertiary: '#6F7787' },
      border: '#1E232C',
      glassBorder: '#2A313D',
      success: '#7CF5C4',
      warning: '#F5C97C',
      danger: '#FF7A8A',
      gradient: ['#0F1318', '#181F2A'] as const,
    },
    light: {
      accent: '#0FB37C',
      accentSoft: '#D5F3E6',
      accentStrong: '#0A8C61',
      secondary: '#3461E8',
      background: { primary: '#F7F8FA', secondary: '#FFFFFF', tertiary: '#EDEFF3' },
      text: { primary: '#0A0A0B', secondary: '#454B57', tertiary: '#7A8190' },
      border: '#E1E4EA',
      glassBorder: '#D4D8E0',
      success: '#0FB37C',
      warning: '#C2851A',
      danger: '#D8344A',
      gradient: ['#FFFFFF', '#EFF2F7'] as const,
    },
  },
  graphite: {
    dark: {
      accent: '#E5E7EB',
      accentSoft: '#2A2D33',
      accentStrong: '#FFFFFF',
      secondary: '#9CA3AF',
      background: { primary: '#08090B', secondary: '#101216', tertiary: '#1A1D22' },
      text: { primary: '#F4F4F5', secondary: '#A1A1AA', tertiary: '#6B7280' },
      border: '#22252B',
      glassBorder: '#2F333A',
      success: '#5BD79A',
      warning: '#F5C97C',
      danger: '#F87171',
      gradient: ['#101216', '#1A1D22'] as const,
    },
    light: {
      accent: '#111111',
      accentSoft: '#E4E4E7',
      accentStrong: '#000000',
      secondary: '#52525B',
      background: { primary: '#FAFAFA', secondary: '#FFFFFF', tertiary: '#F4F4F5' },
      text: { primary: '#0A0A0B', secondary: '#3F3F46', tertiary: '#71717A' },
      border: '#E4E4E7',
      glassBorder: '#D4D4D8',
      success: '#0FB37C',
      warning: '#B45309',
      danger: '#B91C1C',
      gradient: ['#FFFFFF', '#F4F4F5'] as const,
    },
  },
  ember: {
    dark: {
      accent: '#FF9F6B',
      accentSoft: '#3A1F18',
      accentStrong: '#FF7A3D',
      secondary: '#FFD27D',
      background: { primary: '#0B0807', secondary: '#161110', tertiary: '#221A18' },
      text: { primary: '#FBF6F2', secondary: '#C9B6AC', tertiary: '#8A7669' },
      border: '#2A201D',
      glassBorder: '#3A2C27',
      success: '#7CF5C4',
      warning: '#FFD27D',
      danger: '#FF6B7A',
      gradient: ['#161110', '#221A18'] as const,
    },
    light: {
      accent: '#D9572E',
      accentSoft: '#FCE3D6',
      accentStrong: '#B33F1A',
      secondary: '#A65C18',
      background: { primary: '#FBF7F4', secondary: '#FFFFFF', tertiary: '#F2EAE3' },
      text: { primary: '#0A0A0B', secondary: '#4A3A30', tertiary: '#7B6A60' },
      border: '#E4D7CD',
      glassBorder: '#D6C6BA',
      success: '#0FB37C',
      warning: '#B45309',
      danger: '#B91C1C',
      gradient: ['#FFFFFF', '#F2EAE3'] as const,
    },
  },
  lagoon: {
    dark: {
      accent: '#5FB6FF',
      accentSoft: '#13243A',
      accentStrong: '#2E96FF',
      secondary: '#7CF5C4',
      background: { primary: '#070A0E', secondary: '#0E1420', tertiary: '#171F2C' },
      text: { primary: '#F2F6FB', secondary: '#A6B4C9', tertiary: '#6E7C90' },
      border: '#1B2533',
      glassBorder: '#28344A',
      success: '#7CF5C4',
      warning: '#F5C97C',
      danger: '#FF7A8A',
      gradient: ['#0E1420', '#1A2538'] as const,
    },
    light: {
      accent: '#1672D2',
      accentSoft: '#D8ECFB',
      accentStrong: '#0E5AA8',
      secondary: '#0FA37C',
      background: { primary: '#F5F8FB', secondary: '#FFFFFF', tertiary: '#EAF1F8' },
      text: { primary: '#0A0A0B', secondary: '#3E4A5C', tertiary: '#6F7C90' },
      border: '#DDE5EF',
      glassBorder: '#CFD9E5',
      success: '#0FB37C',
      warning: '#B45309',
      danger: '#B91C1C',
      gradient: ['#FFFFFF', '#EAF1F8'] as const,
    },
  },
};

interface ThemeContextValue {
  readonly theme: ThemePalette;
  readonly themeId: ThemeId;
  readonly themeMode: ThemeMode;
  readonly tintIntensity: TintIntensity;
  readonly increaseContrast: boolean;
  readonly resolvedMode: 'light' | 'dark';
  readonly setTheme: (id: ThemeId) => void;
  readonly setThemeMode: (mode: ThemeMode) => void;
  readonly setTintIntensity: (intensity: TintIntensity) => void;
  readonly setIncreaseContrast: (value: boolean) => void;
  readonly themes: typeof THEMES;
}

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

const tintAmount: Record<TintIntensity, number> = {
  subtle: 0.6,
  balanced: 1.0,
  vivid: 1.25,
};

const mixHex = (hex: string, target: string, weight: number): string => {
  const clean = (s: string) => s.replace('#', '');
  const a = clean(hex);
  const b = clean(target);
  const parse = (s: string, i: number) => parseInt(s.slice(i, i + 2), 16);
  const r = Math.round(parse(a, 0) * (1 - weight) + parse(b, 0) * weight);
  const g = Math.round(parse(a, 2) * (1 - weight) + parse(b, 2) * weight);
  const bl = Math.round(parse(a, 4) * (1 - weight) + parse(b, 4) * weight);
  return `#${[r, g, bl].map((v) => v.toString(16).padStart(2, '0')).join('')}`;
};

const applyTint = (palette: ThemePalette, intensity: TintIntensity): ThemePalette => {
  if (intensity === 'balanced') return palette;
  const w = tintAmount[intensity];
  const adjust = (hex: string) =>
    intensity === 'subtle'
      ? mixHex(hex, palette.background.primary, 1 - w)
      : mixHex(hex, palette.accentStrong, Math.min(0.5, (w - 1) * 1.2));
  return {
    ...palette,
    accent: adjust(palette.accent),
  };
};

export function ThemeProvider({ children }: { readonly children: ReactNode }) {
  const [themeId, setThemeId] = useState<ThemeId>('aurora');
  const [themeMode, setThemeMode] = useState<ThemeMode>('auto');
  const [tintIntensity, setTintIntensity] = useState<TintIntensity>('balanced');
  const [increaseContrast, setIncreaseContrast] = useState(false);
  const [systemScheme, setSystemScheme] = useState<'light' | 'dark'>(
    Appearance.getColorScheme() === 'light' ? 'light' : 'dark',
  );

  useEffect(() => {
    const sub = Appearance.addChangeListener(({ colorScheme }) => {
      setSystemScheme(colorScheme === 'light' ? 'light' : 'dark');
    });
    return () => sub.remove();
  }, []);

  const resolvedMode: 'light' | 'dark' = themeMode === 'auto' ? systemScheme : themeMode;

  const theme = useMemo<ThemePalette>(() => {
    const bundle = THEMES[themeId];
    const base = resolvedMode === 'light' ? bundle.light : bundle.dark;
    const tinted = applyTint(base, tintIntensity);
    if (!increaseContrast) return tinted;
    return {
      ...tinted,
      text: {
        primary: resolvedMode === 'light' ? '#000000' : '#FFFFFF',
        secondary: tinted.text.secondary,
        tertiary: tinted.text.tertiary,
      },
      border: resolvedMode === 'light' ? '#000000' : '#FFFFFF',
    };
  }, [themeId, resolvedMode, tintIntensity, increaseContrast]);

  const value = useMemo<ThemeContextValue>(
    () => ({
      theme,
      themeId,
      themeMode,
      tintIntensity,
      increaseContrast,
      resolvedMode,
      setTheme: setThemeId,
      setThemeMode,
      setTintIntensity,
      setIncreaseContrast,
      themes: THEMES,
    }),
    [theme, themeId, themeMode, tintIntensity, increaseContrast, resolvedMode],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside <ThemeProvider>');
  return ctx;
}
