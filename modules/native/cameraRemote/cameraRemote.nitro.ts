/*
 * mi-band-9-active — Nitro HybridObject spec for the band's camera-remote app.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiSystemService
 *     (CMD_CAMERA_REMOTE_GET = 7 / _SET = 8, proto System.camera { enabled })
 *
 * Upstream only toggles whether the band shows its camera-remote app. It has
 * NO handler for a shutter event, so `onShutter` only fires for an unsolicited
 * band message carrying `System.camera` — unverified on hardware, see report.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface HybridCameraRemote extends HybridObject<{ android: 'kotlin' }> {
  /** Band-reported (or pending user) value; undefined if unknown. */
  getEnabled(): boolean | undefined;
  refresh(): Promise<boolean | undefined>;
  /** Persist + push (or queue until connect). */
  setEnabled(enabled: boolean): Promise<boolean>;
  /** Fires when the band reports a shutter press (unverified wire format). */
  onShutter(listener: () => void): () => void;
}
