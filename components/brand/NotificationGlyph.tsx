/*
 * mi-band-9-active — vector notification glyph (silhouette of a wristband
 * with a glyph dot). Designed to be flattened into a white-on-transparent
 * PNG for Android notification small icon.
 *
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import Svg, { Path } from 'react-native-svg';

export interface NotificationGlyphProps {
  readonly size?: number;
  readonly color?: string;
}

export function NotificationGlyph({ size = 24, color = '#FFFFFF' }: NotificationGlyphProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24">
      <Path
        d="M12 3
           a4 4 0 0 1 4 4
           v1
           h-8
           v-1
           a4 4 0 0 1 4 -4 z"
        fill={color}
      />
      <Path
        d="M7 9
           h10
           a1 1 0 0 1 1 1
           v6
           a1 1 0 0 1 -1 1
           h-10
           a1 1 0 0 1 -1 -1
           v-6
           a1 1 0 0 1 1 -1 z"
        fill={color}
      />
      <Path
        d="M12 21
           a4 4 0 0 0 4 -4
           v-1
           h-8
           v1
           a4 4 0 0 0 4 4 z"
        fill={color}
      />
    </Svg>
  );
}
