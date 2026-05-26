/*
 * mi-band-9-active — Nitro HybridObject spec for watchface install / list / delete.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - XiaomiWatchfaceService (commands type=4 subtype=0/1/2/4)
 *   - XiaomiDataUploadService (TYPE_WATCHFACE = 16)
 *   - XiaomiInstallHandler (file validation)
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface WatchfaceInfo {
  readonly id: string;
  readonly name: string;
  readonly canDelete: boolean;
  readonly active: boolean;
}

export interface HybridWatchface
  extends HybridObject<{ android: 'kotlin' }> {
  list(): Promise<readonly WatchfaceInfo[]>;
  /** `localFilePath` is an absolute path to a .bin file on the device. */
  install(localFilePath: string, watchfaceId: string): Promise<number>;
  setActive(watchfaceId: string): Promise<void>;
  /** `delete` is a C++ keyword, so the Nitro spec uses `remove` instead. */
  remove(watchfaceId: string): Promise<void>;
  onInstallProgress(listener: (percent: number) => void): () => void;
}
