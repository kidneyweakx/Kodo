/*
 * mi-band-9-active — bottom tab layout (Today / Notifications / Settings).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { Tabs } from 'expo-router';
import { Platform } from 'react-native';
import { BlurView } from 'expo-blur';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { ThemedText } from '@/components/themed';
import { NotificationsIcon, SettingsIcon, TodayIcon } from '@/components/brand/TabIcons';

function TabBarLabel({ label, focused }: { readonly label: string; readonly focused: boolean }) {
  return (
    <ThemedText
      variant="caption"
      tone={focused ? 'accent' : 'tertiary'}
      style={{ marginTop: 2 }}
    >
      {label}
    </ThemedText>
  );
}

export default function TabsLayout() {
  const { theme, resolvedMode } = useTheme();
  const insets = useSafeAreaInsets();
  // Total tab bar height = tab content (60) + system gesture/3-button nav.
  // On gesture nav inset is ~20, on 3-button it's ~48; either way we let
  // SafeArea decide so we never bury the labels under the Android nav bar.
  const tabContentHeight = 60;
  const tabBarHeight = tabContentHeight + insets.bottom;
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarShowLabel: true,
        tabBarActiveTintColor: theme.accent,
        tabBarInactiveTintColor: theme.text.tertiary,
        tabBarStyle: {
          position: 'absolute',
          backgroundColor: Platform.select({
            android: theme.background.secondary,
            default: 'transparent',
          }),
          borderTopColor: theme.glassBorder,
          height: tabBarHeight,
          paddingTop: Spacing.sm,
          paddingBottom: insets.bottom + Spacing.xs,
        },
        tabBarBackground: () => (
          <BlurView
            tint={resolvedMode === 'light' ? 'light' : 'dark'}
            intensity={Platform.OS === 'android' ? 0 : 30}
            style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
          />
        ),
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: 'Today',
          tabBarIcon: ({ focused, color }) => <TodayIcon focused={focused} color={typeof color === 'string' ? color : undefined} size={24} />,
          tabBarLabel: ({ focused }) => <TabBarLabel label="Today / 今日" focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="notifications"
        options={{
          title: 'Notifications',
          tabBarIcon: ({ focused, color }) => <NotificationsIcon focused={focused} color={typeof color === 'string' ? color : undefined} size={24} />,
          tabBarLabel: ({ focused }) => <TabBarLabel label="通知 / Notif" focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: 'Settings',
          tabBarIcon: ({ focused, color }) => <SettingsIcon focused={focused} color={typeof color === 'string' ? color : undefined} size={24} />,
          tabBarLabel: ({ focused }) => <TabBarLabel label="設定 / Settings" focused={focused} />,
        }}
      />
    </Tabs>
  );
}
