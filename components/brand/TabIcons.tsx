/*
 * mi-band-9-active — vector tab bar icons (Today / Notifications / Settings).
 * Matches the NotificationGlyph styling: 24x24 viewBox, single color prop,
 * stroke-based so the accent tint flows through.
 *
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import Svg, { Circle, Path } from 'react-native-svg';

export interface TabIconProps {
  readonly size?: number;
  readonly color?: string;
  readonly focused?: boolean;
}

const STROKE = 1.9;

export function TodayIcon({ size = 24, color = '#FFFFFF', focused = false }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M12 20.5s-7-4.35-7-9.6a4.4 4.4 0 0 1 7-3.55 4.4 4.4 0 0 1 7 3.55c0 5.25-7 9.6-7 9.6z"
        stroke={color}
        strokeWidth={STROKE}
        strokeLinecap="round"
        strokeLinejoin="round"
        fill={focused ? color : 'none'}
        fillOpacity={focused ? 0.18 : 0}
      />
    </Svg>
  );
}

export function NotificationsIcon({ size = 24, color = '#FFFFFF', focused = false }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M6 16.5h12l-1.5-2v-4a4.5 4.5 0 0 0-9 0v4z"
        stroke={color}
        strokeWidth={STROKE}
        strokeLinecap="round"
        strokeLinejoin="round"
        fill={focused ? color : 'none'}
        fillOpacity={focused ? 0.18 : 0}
      />
      <Path
        d="M10.5 19.5a1.5 1.5 0 0 0 3 0"
        stroke={color}
        strokeWidth={STROKE}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

export function SettingsIcon({ size = 24, color = '#FFFFFF', focused = false }: TabIconProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M12 3.5l1.6 2.2 2.7-.4.7 2.6 2.4 1.3-1 2.5 1 2.5-2.4 1.3-.7 2.6-2.7-.4L12 20.5l-1.6-2.2-2.7.4-.7-2.6L4.6 14.8l1-2.5-1-2.5L7 8.5l.7-2.6 2.7.4z"
        stroke={color}
        strokeWidth={STROKE}
        strokeLinecap="round"
        strokeLinejoin="round"
        fill={focused ? color : 'none'}
        fillOpacity={focused ? 0.18 : 0}
      />
      <Circle cx={12} cy={12} r={2.6} stroke={color} strokeWidth={STROKE} />
    </Svg>
  );
}
