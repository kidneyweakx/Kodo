/*
 * mi-band-9-active — ThemedText: typography + tone, never raw fontSize.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { Text } from 'react-native';
import type { TextProps, TextStyle } from 'react-native';

import { Typography } from '@/constants/DesignSystem';
import type { TypographyVariant } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';

export type ThemedTextTone = 'primary' | 'secondary' | 'tertiary' | 'accent' | 'success' | 'warning' | 'error' | 'inverse';

export interface ThemedTextProps extends TextProps {
  readonly variant?: TypographyVariant;
  readonly tone?: ThemedTextTone;
  readonly weight?: TextStyle['fontWeight'];
  readonly align?: TextStyle['textAlign'];
}

export function ThemedText({
  variant = 'bodyMedium',
  tone = 'primary',
  weight,
  align,
  style,
  children,
  ...rest
}: ThemedTextProps) {
  const { theme } = useTheme();

  const color =
    tone === 'accent' ? theme.accent
      : tone === 'success' ? theme.success
        : tone === 'warning' ? theme.warning
          : tone === 'error' ? theme.danger
            : tone === 'inverse' ? theme.background.primary
              : tone === 'tertiary' ? theme.text.tertiary
                : tone === 'secondary' ? theme.text.secondary
                  : theme.text.primary;

  return (
    <Text
      {...rest}
      style={[Typography[variant], { color }, weight ? { fontWeight: weight } : null, align ? { textAlign: align } : null, style]}
    >
      {children}
    </Text>
  );
}
