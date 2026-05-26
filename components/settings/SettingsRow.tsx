/*
 * mi-band-9-active — settings-row primitive (iOS Settings energy, M3 spirit).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * One row in a sectioned settings list. The grouping container is just a
 * `ThemedSurface variant="elevated"` with the rows stacked inside and a
 * hair-line divider between each.
 *
 * Variants:
 *   - `kind="nav"`     → label + optional value + chevron, tappable
 *   - `kind="switch"`  → label + Switch on the right
 *   - `kind="display"` → static label + value, no tap
 *   - `kind="action"`  → label only, tappable, accent text on the right ("Open")
 */

import { ReactNode } from 'react';
import { Pressable, Switch, View } from 'react-native';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

export type SettingsRowKind = 'nav' | 'switch' | 'display' | 'action' | 'custom';

export interface SettingsRowProps {
  readonly kind: SettingsRowKind;
  readonly label: string;
  readonly sublabel?: string;
  readonly value?: string;
  readonly tone?: 'default' | 'accent' | 'destructive';
  readonly switchValue?: boolean;
  readonly disabled?: boolean;
  readonly first?: boolean;
  readonly last?: boolean;
  readonly onPress?: () => void;
  readonly onSwitchChange?: (v: boolean) => void;
  /** When `kind="custom"`, render this in the right slot. */
  readonly accessory?: ReactNode;
  /** When `kind="custom"`, also render this below the label row (full width). */
  readonly belowAccessory?: ReactNode;
}

export function SettingsRow({
  kind,
  label,
  sublabel,
  value,
  tone = 'default',
  switchValue,
  disabled,
  first,
  last,
  onPress,
  onSwitchChange,
  accessory,
  belowAccessory,
}: SettingsRowProps) {
  const { theme } = useTheme();
  const labelColor =
    tone === 'destructive' ? theme.danger
      : tone === 'accent' ? theme.accent
        : theme.text.primary;

  const valueColor = tone === 'accent' ? theme.accent : theme.text.tertiary;

  const Inner = (
    <View
      style={{
        paddingHorizontal: Spacing.lg,
        paddingVertical: Spacing.md,
        borderTopLeftRadius: first ? Radius.lg : 0,
        borderTopRightRadius: first ? Radius.lg : 0,
        borderBottomLeftRadius: last ? Radius.lg : 0,
        borderBottomRightRadius: last ? Radius.lg : 0,
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.md, minHeight: 28 }}>
        <View style={{ flex: 1, gap: sublabel ? 2 : 0 }}>
          <ThemedText variant="titleMedium" style={{ color: labelColor }}>
            {label}
          </ThemedText>
          {sublabel ? (
            <ThemedText variant="caption" tone="tertiary">
              {sublabel}
            </ThemedText>
          ) : null}
        </View>
        {kind === 'nav' ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            {value ? (
              <ThemedText variant="bodyMedium" style={{ color: valueColor }}>
                {value}
              </ThemedText>
            ) : null}
            <ThemedText variant="titleMedium" style={{ color: theme.text.tertiary }}>
              ›
            </ThemedText>
          </View>
        ) : kind === 'switch' ? (
          <Switch
            value={switchValue ?? false}
            onValueChange={onSwitchChange}
            trackColor={{ false: theme.glassBorder, true: theme.accent }}
            thumbColor="#FFFFFF"
            disabled={disabled}
          />
        ) : kind === 'display' ? (
          value ? (
            <ThemedText variant="bodyMedium" style={{ color: valueColor }}>
              {value}
            </ThemedText>
          ) : null
        ) : kind === 'action' ? (
          <ThemedText variant="bodyMedium" style={{ color: tone === 'destructive' ? theme.danger : theme.accent }}>
            {value ?? 'Open'}
          </ThemedText>
        ) : (
          accessory
        )}
      </View>
      {belowAccessory ? <View style={{ marginTop: Spacing.sm }}>{belowAccessory}</View> : null}
    </View>
  );

  if (kind === 'switch' || kind === 'display' || (kind === 'custom' && !onPress)) {
    return Inner;
  }
  return (
    <Pressable
      onPress={disabled ? undefined : onPress}
      accessibilityRole="button"
      android_ripple={{ color: theme.glassBorder }}
    >
      {Inner}
    </Pressable>
  );
}

/** Container wrapping a stack of rows with shared elevated surface + hair-line dividers. */
export function SettingsGroup({ children }: { readonly children: ReactNode }) {
  const { theme } = useTheme();
  return (
    <View
      style={{
        borderRadius: Radius.lg,
        backgroundColor: theme.background.secondary,
        borderWidth: 1,
        borderColor: theme.glassBorder,
        overflow: 'hidden',
      }}
    >
      {children}
    </View>
  );
}

/** Single-pixel divider between rows. Use between each <SettingsRow/>. */
export function SettingsDivider() {
  const { theme } = useTheme();
  return (
    <View
      style={{
        height: 1,
        backgroundColor: theme.glassBorder,
        marginHorizontal: Spacing.lg,
        opacity: 0.5,
      }}
    />
  );
}

/** Section header eyebrow + subtitle. */
export function SettingsSection({
  title,
  caption,
  children,
}: {
  readonly title: string;
  readonly caption?: string;
  readonly children: ReactNode;
}) {
  return (
    <View style={{ gap: Spacing.sm }}>
      <View style={{ paddingHorizontal: Spacing.sm, gap: 2 }}>
        <ThemedText variant="eyebrow" tone="tertiary">
          {title}
        </ThemedText>
        {caption ? (
          <ThemedText variant="caption" tone="tertiary">
            {caption}
          </ThemedText>
        ) : null}
      </View>
      <SettingsGroup>{children}</SettingsGroup>
    </View>
  );
}
