/*
 * mi-band-9-active — ThemedSurface: card / elevated / outlined containers.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { View } from 'react-native';
import type { ViewProps } from 'react-native';

import { Radius, Shadow, Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';

export type ThemedSurfaceVariant = 'card' | 'elevated' | 'outlined' | 'flat';

export interface ThemedSurfaceProps extends ViewProps {
  readonly variant?: ThemedSurfaceVariant;
  readonly padded?: boolean | keyof typeof Spacing;
  readonly radius?: keyof typeof Radius;
}

export function ThemedSurface({
  variant = 'card',
  padded = false,
  radius = 'lg',
  style,
  children,
  ...rest
}: ThemedSurfaceProps) {
  const { theme } = useTheme();

  const variantStyle =
    variant === 'elevated'
      ? { backgroundColor: theme.background.tertiary, ...Shadow.medium }
      : variant === 'outlined'
        ? { backgroundColor: 'transparent', borderColor: theme.glassBorder, borderWidth: 1 }
        : variant === 'flat'
          ? { backgroundColor: theme.background.secondary }
          : {
              backgroundColor: theme.background.secondary,
              borderColor: theme.glassBorder,
              borderWidth: 1,
              ...Shadow.subtle,
            };

  const paddingValue = padded === true ? Spacing.lg : padded ? Spacing[padded] : 0;

  return (
    <View
      {...rest}
      style={[
        { borderRadius: Radius[radius], padding: paddingValue, overflow: 'hidden' },
        variantStyle,
        style,
      ]}
    >
      {children}
    </View>
  );
}
