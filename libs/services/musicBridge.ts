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

/*
 * JS facade over HybridMusicBridge. Now-playing sync and band button
 * dispatch are native (MediaSessionManager via the notification-listener
 * grant); JS only observes.
 */

import { useEffect, useState } from 'react';

import { NativeMusicBridge } from '@/modules/native';
import { safeCall, safeUnsubscribe } from '@/modules/native/safe';
import type { MusicNowPlaying } from '@/modules/native';
import type { MusicCommand } from '@/modules/native/music/music.nitro';

export type { MusicCommand };

export const musicBridge = {
  /** Real now-playing from the active media session, or null. */
  getNowPlayingSync(): MusicNowPlaying | null {
    return safeCall(() => NativeMusicBridge().getNowPlaying() ?? null, null);
  },

  /** Re-read the media session and push to the band (if connected). */
  refresh(): void {
    safeCall(() => NativeMusicBridge().refresh(), undefined);
  },

  /** Only used when no Android media session is active. */
  pushNowPlaying(snapshot: MusicNowPlaying): void {
    safeCall(() => NativeMusicBridge().pushNowPlaying(snapshot), undefined);
  },

  /** Band button presses, reported after native dispatch. Informational only. */
  onCommand(listener: (command: MusicCommand) => void): () => void {
    return safeUnsubscribe(() => NativeMusicBridge().onCommand(listener));
  },
};

/** Last band music command (e.g. for a transient toast); null until one arrives. */
export function useLastBandMusicCommand(): MusicCommand | null {
  const [last, setLast] = useState<MusicCommand | null>(null);
  useEffect(() => musicBridge.onCommand((cmd) => setLast(cmd)), []);
  return last;
}
