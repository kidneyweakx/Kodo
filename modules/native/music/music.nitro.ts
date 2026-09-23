/*
 * mi-band-9-active — Nitro HybridObject spec for music remote.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiMusicService
 *   - nodomain.freeyourgadget.gadgetbridge.util.MediaManager
 *   - nodomain.freeyourgadget.gadgetbridge.service.receivers.GBMusicControlReceiver
 *
 * Now-playing tracking and band button dispatch are native (MediaSessionManager
 * via the notification-listener grant). JS only observes.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { MusicNowPlaying } from '../types';

export type MusicCommand = 'play' | 'pause' | 'next' | 'previous' | 'volumeUp' | 'volumeDown';

export interface HybridMusicBridge
  extends HybridObject<{ android: 'kotlin' }> {
  /**
   * Push a snapshot to the band. Only used when no Android media session is
   * active; a live session always wins. Dropped when the band is disconnected.
   */
  pushNowPlaying(snapshot: MusicNowPlaying): void;

  /**
   * Fires after a band button has already been dispatched to the active media
   * session natively. Informational — JS must not re-dispatch it.
   */
  onCommand(listener: (command: MusicCommand) => void): () => void;

  /**
   * What the active Android media session reports right now, or undefined
   * when nothing is playing / notification access is not granted.
   */
  getNowPlaying(): MusicNowPlaying | undefined;

  /** Re-read the active media session and push it to the band if connected. */
  refresh(): void;
}
