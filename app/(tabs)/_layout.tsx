/*
 * mi-band-9-active — bottom tab layout (Today / Notifications / Settings).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { Tabs } from 'expo-router';
import { View } from 'react-native';

import { Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { ThemedText } from '@/components/themed';

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

function TabDot({ focused }: { readonly focused: boolean }) {
  const { theme } = useTheme();
  return (
    <View
      style={{
        width: focused ? 14 : 6,
        height: 6,
        borderRadius: 3,
        backgroundColor: focused ? theme.accent : theme.text.tertiary,
        marginTop: 4,
      }}
    />
  );
}

export default function TabsLayout() {
  const { theme } = useTheme();
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarShowLabel: true,
        tabBarStyle: {
          backgroundColor: theme.background.secondary,
          borderTopColor: theme.glassBorder,
          height: 68,
          paddingTop: Spacing.sm,
        },
      }}
    >
      <Tabs.Screen
        name="index"
        options={{
          title: 'Today',
          tabBarIcon: ({ focused }) => <TabDot focused={focused} />,
          tabBarLabel: ({ focused }) => <TabBarLabel label="Today / 今日" focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="notifications"
        options={{
          title: 'Notifications',
          tabBarIcon: ({ focused }) => <TabDot focused={focused} />,
          tabBarLabel: ({ focused }) => <TabBarLabel label="通知 / Notif" focused={focused} />,
        }}
      />
      <Tabs.Screen
        name="settings"
        options={{
          title: 'Settings',
          tabBarIcon: ({ focused }) => <TabDot focused={focused} />,
          tabBarLabel: ({ focused }) => <TabBarLabel label="設定 / Settings" focused={focused} />,
        }}
      />
    </Tabs>
  );
}
