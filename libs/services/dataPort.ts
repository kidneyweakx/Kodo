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
 *
 * JS facade over HybridDataPort: Kodō backup / restore and Gadgetbridge
 * import. `uri` may be a content:// URI (document picker, no cache copy
 * needed), a file:// URI or an absolute path.
 *
 * Errors propagate as-is — a failed export/restore/import must surface to the
 * user, never turn into a plausible default. Rejections carry a
 * `CODE: message` string (BUSY, BAD_FILE, NOT_KODO, NOT_GADGETBRIDGE,
 * NEWER_FORMAT, DEVICE_NOT_FOUND, IO); see `dataPortErrorCode`.
 */

import { NativeDataPort } from '@/modules/native';
import { safeUnsubscribe } from '@/modules/native/safe';
import type {
  BackupInfo,
  BackupSummary,
  GadgetbridgeDevice,
  ImportSummary,
  RestoreResult,
} from '@/modules/native/dataPort/dataPort.nitro';

export type {
  BackupFormat,
  BackupInfo,
  BackupSummary,
  GadgetbridgeDevice,
  ImportSummary,
  RestoreResult,
} from '@/modules/native/dataPort/dataPort.nitro';

export type DataPortErrorCode =
  | 'BUSY'
  | 'BAD_FILE'
  | 'NOT_KODO'
  | 'NOT_GADGETBRIDGE'
  | 'NEWER_FORMAT'
  | 'DEVICE_NOT_FOUND'
  | 'IO';

const CODES: readonly DataPortErrorCode[] = [
  'BUSY',
  'BAD_FILE',
  'NOT_KODO',
  'NOT_GADGETBRIDGE',
  'NEWER_FORMAT',
  'DEVICE_NOT_FOUND',
  'IO',
];

/** Extracts the `CODE:` prefix of a rejected data-port call, or null. */
export const dataPortErrorCode = (error: unknown): DataPortErrorCode | null => {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return CODES.find((c) => message.includes(`${c}:`)) ?? null;
};

export const dataPort = {
  exportBackup(includeAuthKey: boolean, appPrefsJson: string): Promise<BackupSummary> {
    return NativeDataPort().exportBackup(includeAuthKey, appPrefsJson);
  },
  inspect(uri: string): Promise<BackupInfo> {
    return NativeDataPort().inspect(uri);
  },
  restoreBackup(uri: string): Promise<RestoreResult> {
    return NativeDataPort().restoreBackup(uri);
  },
  inspectGadgetbridge(uri: string): Promise<readonly GadgetbridgeDevice[]> {
    return NativeDataPort().inspectGadgetbridge(uri);
  },
  importGadgetbridge(uri: string, deviceAddress: string): Promise<ImportSummary> {
    return NativeDataPort().importGadgetbridge(uri, deviceAddress);
  },
  onProgress(listener: (phase: string, progress: number) => void): () => void {
    return safeUnsubscribe(() => NativeDataPort().onProgress(listener));
  },
};
