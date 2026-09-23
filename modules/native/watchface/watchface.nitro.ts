/*
 * mi-band-9-active — Nitro HybridObject spec for watchface install / list / delete.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - XiaomiWatchfaceService (commands type=4 subtype=0/1/2/4)
 *   - XiaomiDataUploadService (type 22, TYPE_WATCHFACE = 16)
 *   - XiaomiFWHelper.parseAsWatchface (0x5A 0xA5 magic, id @0x28, name @0x68)
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface WatchfaceInfo {
  readonly id: string;
  readonly name: string;
  readonly canDelete: boolean;
  readonly active: boolean;
}

export interface WatchfaceFileInfo {
  /** Numeric id parsed from the file header. */
  readonly id: string;
  /** Face name from the header ('' when only a localized table exists). */
  readonly name: string;
  readonly sizeBytes: number;
}

export interface HybridWatchface extends HybridObject<{ android: 'kotlin' }> {
  /** Last list the band returned, [] if never fetched. */
  getCachedList(): readonly WatchfaceInfo[];
  list(): Promise<readonly WatchfaceInfo[]>;
  /** Validate + parse a watchface file. `uri`: content://, file:// or absolute path. */
  inspect(uri: string): Promise<WatchfaceFileInfo>;
  /** Install (id taken from the file header), activate, and re-list. */
  install(uri: string): Promise<WatchfaceFileInfo>;
  setActive(watchfaceId: string): Promise<void>;
  /** Refuses the active face and built-in (non-deletable) faces, like upstream. */
  remove(watchfaceId: string): Promise<void>;
  onInstallProgress(listener: (percent: number) => void): () => void;
}
