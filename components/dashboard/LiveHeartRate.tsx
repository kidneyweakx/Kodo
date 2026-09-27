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

// Opt-in realtime heart rate (POWER.md rule 6). Ticks land in a ref ring
// buffer; React only sees a 500 ms throttled mirror of the latest value plus
// the buffer for the sparkline (CLAUDE.md rule 9). Native auto-stops on
// unsubscribe, disconnect, or after 5 minutes; we mirror that cap in the UI.

import { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';

import { Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText } from '@/components/themed';
import { Sparkline } from '@/components/dashboard/Sparkline';
import { bandLink, useConnectionState } from '@/libs/services/bandLink';
import { t } from '@/libs/services/i18n';

const MAX_MS = 5 * 60_000;
const RING = 60;

export function LiveHeartRate() {
  const connected = useConnectionState() === 'connected';
  const [running, setRunning] = useState(false);
  const [display, setDisplay] = useState<{ bpm: number | null; series: number[] }>({ bpm: null, series: [] });
  const ring = useRef<number[]>([]);
  const dirty = useRef(false);

  useEffect(() => {
    if (!running || !connected) return;
    ring.current = [];
    const unsubscribe = bandLink.onRealtimeHeartRate((bpm) => {
      ring.current.push(bpm);
      if (ring.current.length > RING) ring.current.shift();
      dirty.current = true;
    });
    bandLink.startRealtimeHeartRate();
    const tick = setInterval(() => {
      if (!dirty.current) return;
      dirty.current = false;
      const series = ring.current.slice();
      setDisplay({ bpm: series[series.length - 1] ?? null, series });
    }, 500);
    const cap = setTimeout(() => setRunning(false), MAX_MS);
    return () => {
      clearInterval(tick);
      clearTimeout(cap);
      unsubscribe();
      bandLink.stopRealtimeHeartRate();
    };
  }, [running, connected]);

  const live = running && connected;

  return (
    <View style={{ gap: Spacing.md }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.md }}>
        <View style={{ flex: 1 }}>
          <ThemedText variant="displayMedium" style={tabularNums}>
            {live && display.bpm != null ? display.bpm : '—'}
            <ThemedText variant="titleMedium" tone="secondary">
              {' '}
              bpm
            </ThemedText>
          </ThemedText>
        </View>
        <Sparkline samples={live ? display.series : []} width={120} height={42} />
      </View>
      <ThemedText variant="caption" tone="tertiary">
        {t('settings.connection.liveBody')}
      </ThemedText>
      <ThemedButton
        variant={live ? 'secondary' : 'primary'}
        label={live ? t('settings.connection.liveStop') : t('settings.connection.liveStart')}
        disabled={!connected}
        fullWidth
        onPress={() => setRunning((r) => !r)}
      />
    </View>
  );
}
