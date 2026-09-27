/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Nitro HybridObject spec for backup / restore of Kodō's own data and for
 * importing history from a Gadgetbridge export.
 *
 * Kodō backup = zip: manifest.json (first entry), app/prefs.json (the JS
 * MMKV blob handed to exportBackup), prefs/<sharedPrefsName>.json (native
 * SharedPreferences, Gadgetbridge JsonBackupPreferences shape) and
 * samples/<table>.jsonl (one JSON object per SQLite row).
 *
 * Gadgetbridge input = the "Export zip" of Data management (ZipBackupExportJob:
 * gadgetbridge.json, database/Gadgetbridge, preferences/device_<MAC>.json)
 * or the raw "Export database" SQLite file.
 *
 * All timestamps are ISO-8601 UTC instants; `firstDay` / `lastDay` are LOCAL
 * calendar dates `YYYY-MM-DD`. Every method runs on a background dispatcher;
 * only one export / restore / import runs at a time (a second call rejects
 * with `BUSY: …`). Errors reject with a `CODE: message` string where CODE is
 * one of BUSY, BAD_FILE, NOT_KODO, NOT_GADGETBRIDGE, NEWER_FORMAT,
 * DEVICE_NOT_FOUND, IO.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export type BackupFormat = 'kodo' | 'gadgetbridge' | 'unknown';

export interface BackupSummary {
  /** file:// uri of the zip in the app cache dir (cacheDir/backups). */
  readonly uri: string;
  /** kodo-backup-YYYYMMDD-HHmm.zip */
  readonly fileName: string;
  readonly sizeBytes: number;
  readonly createdAt: string;
  /** Sample rows written (all tables). */
  readonly rowCount: number;
  readonly includesAuthKey: boolean;
}

export interface BackupInfo {
  readonly format: BackupFormat;
  readonly createdAt: string | null;
  readonly appVersion: string | null;
  /**
   * Kodō: sample rows in the backup. Gadgetbridge: rows in the Xiaomi sample
   * tables (all devices). 0 for `unknown`.
   */
  readonly rowCount: number;
  /** Kodō: the band auth key is inside. Gadgetbridge: some Xiaomi device's prefs carry an `authkey`. */
  readonly includesAuthKey: boolean;
  /** Kodō: the paired band's name. Gadgetbridge: the first Xiaomi device (Band 9 Active first). */
  readonly bandName: string | null;
  /** Backup format version (Kodō formatVersion / Gadgetbridge backupVersion); null when unknown. */
  readonly formatVersion: number | null;
}

export interface GadgetbridgeDevice {
  /** DEVICE.IDENTIFIER — the MAC address; pass it to importGadgetbridge. */
  readonly address: string;
  /** DEVICE.ALIAS when set, else DEVICE.NAME. */
  readonly name: string;
  /** DEVICE.TYPE_NAME (e.g. MIBAND9ACTIVE); `legacy:<TYPE>` on databases older than schema 62. */
  readonly type: string;
  /** 32 lowercase hex chars from preferences/device_<MAC>.json `authkey`; null when absent/invalid. */
  readonly authKey: string | null;
  readonly firstSampleAt: string | null;
  readonly lastSampleAt: string | null;
  /** Rows in XIAOMI_ACTIVITY_SAMPLE for this device (minute samples). */
  readonly sampleCount: number;
}

export interface ImportSummary {
  readonly activitySamples: number;
  readonly sleepSessions: number;
  readonly sleepStages: number;
  readonly dailySummaries: number;
  readonly manualSamples: number;
  readonly workouts: number;
  readonly firstDay: string | null;
  readonly lastDay: string | null;
}

export interface RestoreResult {
  readonly imported: ImportSummary;
  /** app/prefs.json exactly as handed to exportBackup; null when the backup has none. */
  readonly appPrefsJson: string | null;
  /** A paired band incl. auth key was restored (reconnect is armed in the background). */
  readonly restoredBand: boolean;
}

export interface HybridDataPort extends HybridObject<{ android: 'kotlin' }> {
  /** Writes a zip into the app cache dir and returns a file:// uri the UI shares via the Android share sheet. */
  exportBackup(includeAuthKey: boolean, appPrefsJson: string): Promise<BackupSummary>;
  /** Reads just the header of a Kodō backup or a Gadgetbridge export (content:// or file://). Never writes. */
  inspect(uri: string): Promise<BackupInfo>;
  /** Restores a Kodō backup (samples + native prefs; returns the JS prefs blob for the JS layer to apply). */
  restoreBackup(uri: string): Promise<RestoreResult>;
  /** Lists Xiaomi devices (any Xiaomi protobuf device, Band 9 Active first) found in a Gadgetbridge export zip or raw database file. */
  inspectGadgetbridge(uri: string): Promise<readonly GadgetbridgeDevice[]>;
  /** Imports that device's history into our store. Idempotent (upserts). */
  importGadgetbridge(uri: string, deviceAddress: string): Promise<ImportSummary>;
  /** 0..1 progress for the running export/restore/import; returns unsubscribe. */
  onProgress(listener: (phase: string, progress: number) => void): () => void;
}
