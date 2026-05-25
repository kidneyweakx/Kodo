/*
 * mi-band-9-active — Nitro HybridObject spec for music remote.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiMusicService
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { MusicNowPlaying } from '../types';

export type MusicCommand = 'play' | 'pause' | 'next' | 'previous' | 'volumeUp' | 'volumeDown';

export interface HybridMusicBridge
  extends HybridObject<{ android: 'kotlin' }> {
  pushNowPlaying(snapshot: MusicNowPlaying): void;
  /** Fires when band-side button commands need to be relayed to OS media controls. */
  onCommand(listener: (command: MusicCommand) => void): () => void;
}
