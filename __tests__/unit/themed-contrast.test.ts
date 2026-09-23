/*
 * mi-band-9-active — contrast helper tests.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { describe, expect, test } from 'bun:test';

import { ON_DARK, ON_LIGHT, contrastRatio, readableTextOn } from '@/components/themed/contrast';

describe('readableTextOn', () => {
  test('light accents get near-black text', () => {
    // Aurora dark accent — white on it is ~1.3:1, the bug this helper exists for.
    expect(readableTextOn('#7CF5C4')).toBe(ON_LIGHT);
  });

  test('dark accents get near-white text', () => {
    expect(readableTextOn('#1B3A2F')).toBe(ON_DARK);
  });

  test('chosen text always clears WCAG AA-large (3:1)', () => {
    for (const bg of ['#7CF5C4', '#0FB37C', '#3461E8', '#FF7A8A', '#F5C97C', '#171C24']) {
      expect(contrastRatio(bg, readableTextOn(bg))).toBeGreaterThanOrEqual(3);
    }
  });
});
