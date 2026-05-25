/*
 * mi-band-9-active — vector app logo.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Geometry: an arc-band (suggesting a wrist strap) hugging a circular dial
 * with a single highlight notch at 12-o'clock (the band's physical wake
 * notch). Pure SVG, theme-tinted, no raster.
 */

import Svg, { Circle, Defs, LinearGradient, Path, Stop } from 'react-native-svg';

import { useTheme } from '@/context/ThemeContext';

export interface AppLogoProps {
  readonly size?: number;
  readonly mono?: boolean;
}

export function AppLogo({ size = 96, mono = false }: AppLogoProps) {
  const { theme } = useTheme();
  const accent = mono ? theme.text.primary : theme.accent;
  const soft = mono ? theme.text.primary : theme.accentStrong;
  const dial = mono ? theme.background.primary : theme.background.secondary;

  return (
    <Svg width={size} height={size} viewBox="0 0 96 96">
      <Defs>
        <LinearGradient id="logoStroke" x1="0" y1="0" x2="1" y2="1">
          <Stop offset="0" stopColor={accent} />
          <Stop offset="1" stopColor={soft} />
        </LinearGradient>
      </Defs>

      <Path
        d="M48 10
           a30 30 0 0 1 0 60
           a30 30 0 0 1 0 -60"
        fill="none"
        stroke="url(#logoStroke)"
        strokeWidth={8}
        strokeLinecap="round"
        opacity={0.18}
      />

      <Path
        d="M22 32
           a30 30 0 0 1 52 0"
        fill="none"
        stroke="url(#logoStroke)"
        strokeWidth={8}
        strokeLinecap="round"
      />

      <Path
        d="M22 64
           a30 30 0 0 0 52 0"
        fill="none"
        stroke="url(#logoStroke)"
        strokeWidth={8}
        strokeLinecap="round"
      />

      <Circle cx={48} cy={48} r={20} fill={dial} stroke={accent} strokeWidth={2} />

      <Path d="M48 32 L48 40" stroke={accent} strokeWidth={3} strokeLinecap="round" />

      <Circle cx={48} cy={48} r={3} fill={accent} />
    </Svg>
  );
}
