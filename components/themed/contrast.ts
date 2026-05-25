/*
 * mi-band-9-active — WCAG-aware foreground picker
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Picks a readable text color on top of an arbitrary accent fill, enforcing
 * WCAG AA-large (3:1) for ≥18pt / ≥14pt bold. Ported from aniseekr-expo.
 */

export const ON_DARK = '#F5F5F7';
export const ON_LIGHT = '#0A0A0B';

const hexToRgb = (hex: string): [number, number, number] => {
  const clean = hex.replace('#', '');
  const value = clean.length === 3
    ? clean.split('').map((c) => c + c).join('')
    : clean;
  const r = parseInt(value.slice(0, 2), 16);
  const g = parseInt(value.slice(2, 4), 16);
  const b = parseInt(value.slice(4, 6), 16);
  return [r, g, b];
};

const channelLuminance = (c: number): number => {
  const v = c / 255;
  return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
};

const relativeLuminance = (hex: string): number => {
  const [r, g, b] = hexToRgb(hex);
  return 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b);
};

export const contrastRatio = (a: string, b: string): number => {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const [hi, lo] = la > lb ? [la, lb] : [lb, la];
  return (hi + 0.05) / (lo + 0.05);
};

/**
 * Returns ON_DARK (~white) or ON_LIGHT (~near-black) — whichever yields
 * the better contrast on the supplied accent. Enforces 3:1 minimum.
 */
export const readableTextOn = (background: string): string => {
  const onDark = contrastRatio(background, ON_DARK);
  const onLight = contrastRatio(background, ON_LIGHT);
  if (onDark >= 3 && onDark >= onLight) return ON_DARK;
  if (onLight >= 3) return ON_LIGHT;
  return onDark > onLight ? ON_DARK : ON_LIGHT;
};
