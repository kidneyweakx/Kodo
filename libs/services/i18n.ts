/*
 * mi-band-9-active — i18n facade. Two locales only: zh-Hant and en.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { I18n } from 'i18n-js';
import { getLocales } from 'expo-localization';

import en from '@/locales/en.json';
import zhHant from '@/locales/zh-Hant.json';

export type SupportedLocale = 'zh-Hant' | 'en';

export const SUPPORTED_LOCALES: readonly SupportedLocale[] = ['zh-Hant', 'en'] as const;

const pickLocale = (): SupportedLocale => {
  const device = getLocales();
  for (const entry of device) {
    const tag = entry.languageTag.toLowerCase();
    if (tag.startsWith('zh-hant') || tag === 'zh-tw' || tag === 'zh-hk' || tag === 'zh-mo') {
      return 'zh-Hant';
    }
    if (tag.startsWith('zh')) return 'zh-Hant';
    if (tag.startsWith('en')) return 'en';
  }
  return 'en';
};

export const i18n = new I18n(
  {
    en,
    'zh-Hant': zhHant,
  },
  {
    enableFallback: true,
    defaultLocale: 'en',
    locale: pickLocale(),
  },
);

export const t = (key: string, options?: Record<string, unknown>): string =>
  i18n.t(key, options);

export const setLocale = (locale: SupportedLocale): void => {
  i18n.locale = locale;
};

export const getLocale = (): SupportedLocale =>
  (SUPPORTED_LOCALES.includes(i18n.locale as SupportedLocale) ? i18n.locale : 'en') as SupportedLocale;
