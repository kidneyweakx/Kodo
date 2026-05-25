/*
 * mi-band-9-active — bottom tab layout (Today / Notifications / Settings).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { Tabs } from 'expo-router';
import { Platform, View } from 'react-native';
import { BlurView } from 'expo-blur';

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
  const { theme, resolvedMode } = useTheme();
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
          height: 76,
          paddingTop: Spacing.sm,
          paddingBottom: Spacing.md,
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
