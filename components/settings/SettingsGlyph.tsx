/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */

// Stroke glyphs for settings rows and section headers. Same 24×24 grid and
// stroke weight as components/brand/TabIcons so everything reads as one set.

import Svg, { Circle, Path, Rect } from 'react-native-svg';

export type SettingsGlyphName =
  | 'band'
  | 'heart'
  | 'bell'
  | 'phone'
  | 'music'
  | 'cloud'
  | 'calendar'
  | 'alarm'
  | 'sync'
  | 'database'
  | 'palette'
  | 'globe'
  | 'info'
  | 'battery'
  | 'moon'
  | 'clock'
  | 'wrist'
  | 'walk'
  | 'person'
  | 'shield'
  | 'trash'
  | 'unlink'
  | 'watchface'
  | 'camera'
  | 'chevron';

const STROKE = 1.9;

export function SettingsGlyph({
  name,
  size = 18,
  color,
}: {
  readonly name: SettingsGlyphName;
  readonly size?: number;
  readonly color: string;
}) {
  const p = {
    stroke: color,
    strokeWidth: STROKE,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    fill: 'none',
  };
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      {name === 'band' && (
        <>
          <Rect x={7} y={6.5} width={10} height={11} rx={3.5} {...p} />
          <Path d="M9 6.5 9.6 3h4.8l.6 3.5M9 17.5l.6 3.5h4.8l.6-3.5" {...p} />
        </>
      )}
      {name === 'heart' && (
        <Path d="M12 20s-7-4.3-7-9.5A4.3 4.3 0 0 1 12 7a4.3 4.3 0 0 1 7 3.5C19 15.7 12 20 12 20zM7.5 12h2.5l1.2-2 1.8 4 1.2-2h2.3" {...p} />
      )}
      {name === 'bell' && (
        <>
          <Path d="M6 16.5h12l-1.5-2v-4a4.5 4.5 0 0 0-9 0v4z" {...p} />
          <Path d="M10.5 19.5a1.5 1.5 0 0 0 3 0" {...p} />
        </>
      )}
      {name === 'phone' && (
        <Path d="M6.5 4h3l1.5 4-2 1.3a10 10 0 0 0 5.7 5.7l1.3-2 4 1.5v3a2 2 0 0 1-2 2A15.5 15.5 0 0 1 4.5 6a2 2 0 0 1 2-2z" {...p} />
      )}
      {name === 'music' && (
        <>
          <Path d="M9 17.5V6l10-2v11.5" {...p} />
          <Circle cx={7} cy={17.5} r={2} {...p} />
          <Circle cx={17} cy={15.5} r={2} {...p} />
        </>
      )}
      {name === 'cloud' && <Path d="M7.5 18h9a4 4 0 0 0 .6-7.95A5.5 5.5 0 0 0 6.6 11.2 3.4 3.4 0 0 0 7.5 18z" {...p} />}
      {name === 'calendar' && (
        <>
          <Rect x={4.5} y={5.5} width={15} height={14} rx={3} {...p} />
          <Path d="M4.5 10h15M8.5 3.5v3M15.5 3.5v3" {...p} />
        </>
      )}
      {name === 'alarm' && (
        <>
          <Circle cx={12} cy={13} r={6.5} {...p} />
          <Path d="M12 10v3.2l2 1.3M5 5.5l2.5-2M19 5.5l-2.5-2" {...p} />
        </>
      )}
      {name === 'sync' && (
        <Path d="M19 8a7.5 7.5 0 0 0-13.3.5M5 16a7.5 7.5 0 0 0 13.3-.5M19 4v4h-4M5 20v-4h4" {...p} />
      )}
      {name === 'database' && (
        <>
          <Path d="M5 6.5c0-1.4 3.1-2.5 7-2.5s7 1.1 7 2.5-3.1 2.5-7 2.5-7-1.1-7-2.5z" {...p} />
          <Path d="M5 6.5v11c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-11M5 12c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5" {...p} />
        </>
      )}
      {name === 'palette' && (
        <>
          <Path d="M12 20a8 8 0 1 1 8-8c0 2-1.6 3-3.3 3H15a1.8 1.8 0 0 0-1.3 3.1c.5.5.1 1.9-1.7 1.9z" {...p} />
          <Circle cx={8.5} cy={10.5} r={1} {...p} />
          <Circle cx={12} cy={7.8} r={1} {...p} />
          <Circle cx={15.5} cy={10.5} r={1} {...p} />
        </>
      )}
      {name === 'globe' && (
        <>
          <Circle cx={12} cy={12} r={8} {...p} />
          <Path d="M4 12h16M12 4c2.2 2.3 3.2 5 3.2 8s-1 5.7-3.2 8c-2.2-2.3-3.2-5-3.2-8s1-5.7 3.2-8z" {...p} />
        </>
      )}
      {name === 'info' && (
        <>
          <Circle cx={12} cy={12} r={8} {...p} />
          <Path d="M12 11v5M12 8h.01" {...p} />
        </>
      )}
      {name === 'battery' && (
        <>
          <Rect x={3.5} y={7.5} width={15} height={9} rx={2.5} {...p} />
          <Path d="M21 11v2M7 10.5v3M10 10.5v3" {...p} />
        </>
      )}
      {name === 'moon' && <Path d="M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z" {...p} />}
      {name === 'clock' && (
        <>
          <Circle cx={12} cy={12} r={8} {...p} />
          <Path d="M12 7.5V12l3 2" {...p} />
        </>
      )}
      {name === 'wrist' && <Path d="M8 20v-5.5L6 9.5a1.5 1.5 0 0 1 2.7-1.2L10 11V5a1.5 1.5 0 0 1 3 0v5-1.5a1.5 1.5 0 0 1 3 0V10a1.5 1.5 0 0 1 3 0v4.5L17 20" {...p} />}
      {name === 'walk' && (
        <>
          <Circle cx={13} cy={4.8} r={1.6} {...p} />
          <Path d="M9 21l2.5-6 2.5 2.5V21M11.5 15l1-5.5-3 1.5-1.5 3M12.5 9.5l2 3 3 1" {...p} />
        </>
      )}
      {name === 'person' && (
        <>
          <Circle cx={12} cy={8.5} r={3.5} {...p} />
          <Path d="M5 20a7 7 0 0 1 14 0" {...p} />
        </>
      )}
      {name === 'shield' && <Path d="M12 3.5 5.5 6v5.5c0 4 2.8 7.3 6.5 9 3.7-1.7 6.5-5 6.5-9V6zM9.5 12l1.8 1.8 3.4-3.6" {...p} />}
      {name === 'trash' && <Path d="M5 7h14M10 7V5h4v2M7 7l.8 12h8.4L17 7M10.5 10.5v5M13.5 10.5v5" {...p} />}
      {name === 'unlink' && <Path d="M9.5 14.5 8 16a3 3 0 0 1-4.2-4.2L6 9.6M14.5 9.5 16 8a3 3 0 0 1 4.2 4.2L18 14.4M4 4l16 16" {...p} />}
      {name === 'watchface' && (
        <>
          <Rect x={6.5} y={3.5} width={11} height={17} rx={3.5} {...p} />
          <Path d="M9.5 9h5M9.5 12h3M9.5 15h4" {...p} />
        </>
      )}
      {name === 'camera' && (
        <>
          <Path d="M4.5 8.5a2 2 0 0 1 2-2h2l1.5-2h4l1.5 2h2a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2z" {...p} />
          <Circle cx={12} cy={12.5} r={3.2} {...p} />
        </>
      )}
      {name === 'chevron' && <Path d="M9.5 6l6 6-6 6" {...p} />}
    </Svg>
  );
}
