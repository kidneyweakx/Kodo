/*
 * mi-band-9-active — ThemedButton: contrast-safe, sized for touch targets, with reanimated press.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useCallback } from 'react';
import { ActivityIndicator, Pressable, View } from 'react-native';
import type { GestureResponderEvent, PressableProps, ViewStyle } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';

import { Motion, Radius, Shadow, Size, Spacing, Typography } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { readableTextOn } from '@/components/themed/contrast';
import { ThemedText } from '@/components/themed/ThemedText';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { HapticKind } from '@/modules/haptics/hapticsBridge';

export type ThemedButtonVariant = 'primary' | 'secondary' | 'destructive' | 'ghost';
export type ThemedButtonSize = 'sm' | 'md' | 'lg';

const heightFor = (size: ThemedButtonSize): number =>
  size === 'sm' ? Size.buttonSm : size === 'lg' ? Size.buttonLg : Size.buttonMd;

const paddingFor = (size: ThemedButtonSize): number =>
  size === 'sm' ? Spacing.md : size === 'lg' ? Spacing.xl : Spacing.lg;

const labelVariantFor = (size: ThemedButtonSize) =>
  size === 'sm' ? 'bodyMedium' : size === 'lg' ? 'titleLarge' : 'titleMedium';

export interface ThemedButtonProps extends Omit<PressableProps, 'children' | 'style'> {
  readonly label: string;
  readonly variant?: ThemedButtonVariant;
  readonly size?: ThemedButtonSize;
  readonly fullWidth?: boolean;
  readonly loading?: boolean;
  readonly leftIcon?: (color: string) => React.ReactNode;
  readonly rightIcon?: (color: string) => React.ReactNode;
  readonly haptic?: HapticKind;
  readonly style?: ViewStyle;
}

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

export function ThemedButton({
  label,
  variant = 'primary',
  size = 'md',
  fullWidth,
  loading,
  leftIcon,
  rightIcon,
  haptic,
  disabled,
  onPress,
  style,
  ...rest
}: ThemedButtonProps) {
  const { theme } = useTheme();
  const scale = useSharedValue(1);
  const press = useSharedValue(0);

  const isDisabled = disabled || loading;

  const { bgColor, fgColor, borderColor } = (() => {
    if (variant === 'destructive') {
      return { bgColor: theme.danger, fgColor: readableTextOn(theme.danger), borderColor: 'transparent' };
    }
    if (variant === 'secondary') {
      return { bgColor: theme.background.tertiary, fgColor: theme.text.primary, borderColor: theme.glassBorder };
    }
    if (variant === 'ghost') {
      return { bgColor: 'transparent', fgColor: theme.text.primary, borderColor: 'transparent' };
    }
    return { bgColor: theme.accent, fgColor: readableTextOn(theme.accent), borderColor: 'transparent' };
  })();

  const defaultHaptic: HapticKind =
    haptic ?? (variant === 'destructive' ? 'warning' : variant === 'primary' ? 'tap' : 'selection');

  const onPressIn = useCallback(() => {
    scale.value = withSpring(0.97, Motion.spring.press);
    press.value = withTiming(1, { duration: Motion.duration.micro });
  }, [scale, press]);

  const onPressOut = useCallback(() => {
    scale.value = withSpring(1, Motion.spring.sheet);
    press.value = withTiming(0, { duration: Motion.duration.fast });
  }, [scale, press]);

  const handlePress = useCallback(
    (e: GestureResponderEvent) => {
      if (isDisabled) return;
      void hapticsBridge.fire(defaultHaptic);
      onPress?.(e);
    },
    [isDisabled, defaultHaptic, onPress],
  );

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ scale: scale.value }],
    opacity: isDisabled ? 0.4 : 1 - press.value * 0.08,
  }));

  const accentGlow = variant === 'primary' ? Shadow.glow(bgColor) : Shadow.subtle;

  return (
    <AnimatedPressable
      {...rest}
      disabled={isDisabled}
      onPress={handlePress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      accessibilityRole="button"
      accessibilityState={{ disabled: isDisabled, busy: loading }}
      style={[
        {
          minHeight: heightFor(size),
          paddingHorizontal: paddingFor(size),
          paddingVertical: Spacing.sm,
          borderRadius: Radius.pill,
          backgroundColor: bgColor,
          borderColor,
          borderWidth: variant === 'secondary' ? 1 : 0,
          alignItems: 'center',
          justifyContent: 'center',
          flexDirection: 'row',
          gap: Spacing.sm,
          alignSelf: fullWidth ? 'stretch' : 'flex-start',
          ...accentGlow,
        },
        animatedStyle,
        style,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={fgColor} />
      ) : (
        <>
          {leftIcon ? <View>{leftIcon(fgColor)}</View> : null}
          <ThemedText
            tone="primary"
            variant={labelVariantFor(size)}
            style={{ ...Typography[labelVariantFor(size)], color: fgColor }}
          >
            {label}
          </ThemedText>
          {rightIcon ? <View>{rightIcon(fgColor)}</View> : null}
        </>
      )}
    </AnimatedPressable>
  );
}

export interface ThemedIconButtonProps
  extends Omit<PressableProps, 'children' | 'style'> {
  readonly accessibilityLabel: string;
  readonly icon: (color: string) => React.ReactNode;
  readonly variant?: ThemedButtonVariant;
  readonly size?: ThemedButtonSize;
  readonly haptic?: HapticKind;
  readonly style?: ViewStyle;
}

export function ThemedIconButton({
  icon,
  variant = 'secondary',
  size = 'md',
  haptic = 'selection',
  disabled,
  onPress,
  style,
  ...rest
}: ThemedIconButtonProps) {
  const { theme } = useTheme();
  const scale = useSharedValue(1);

  const { bgColor, fgColor, borderColor } = (() => {
    if (variant === 'destructive') {
      return { bgColor: theme.danger, fgColor: readableTextOn(theme.danger), borderColor: 'transparent' };
    }
    if (variant === 'primary') {
      return { bgColor: theme.accent, fgColor: readableTextOn(theme.accent), borderColor: 'transparent' };
    }
    if (variant === 'ghost') {
      return { bgColor: 'transparent', fgColor: theme.text.primary, borderColor: 'transparent' };
    }
    return { bgColor: theme.background.tertiary, fgColor: theme.text.primary, borderColor: theme.glassBorder };
  })();

  const dim = heightFor(size);

  const onPressIn = useCallback(() => {
    scale.value = withSpring(0.92, Motion.spring.press);
  }, [scale]);

  const onPressOut = useCallback(() => {
    scale.value = withSpring(1, Motion.spring.sheet);
  }, [scale]);

  const handlePress = useCallback(
    (e: GestureResponderEvent) => {
      if (disabled) return;
      void hapticsBridge.fire(haptic);
      onPress?.(e);
    },
    [disabled, haptic, onPress],
  );

  const animatedStyle = useAnimatedStyle(() => ({ transform: [{ scale: scale.value }] }));

  return (
    <AnimatedPressable
      {...rest}
      disabled={disabled}
      onPress={handlePress}
      onPressIn={onPressIn}
      onPressOut={onPressOut}
      accessibilityRole="button"
      style={[
        {
          width: dim,
          height: dim,
          borderRadius: Radius.pill,
          backgroundColor: bgColor,
          borderColor,
          borderWidth: variant === 'secondary' ? 1 : 0,
          alignItems: 'center',
          justifyContent: 'center',
          opacity: disabled ? 0.4 : 1,
        },
        animatedStyle,
        style,
      ]}
    >
      {icon(fgColor)}
    </AnimatedPressable>
  );
}
