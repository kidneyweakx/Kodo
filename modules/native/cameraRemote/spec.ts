/*
 * mi-band-9-active — Nitro HybridObject spec for band-triggered camera shutter.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiSystemService
 *     (handleCameraRemote, setCameraRemoteConfig, PREF_CAMERA_REMOTE)
 *
 * Flow: app opens CameraX preview → user wears band → presses shutter on band →
 * native onShutter -> JS facade fires camera.takePicture(). Power profile: the
 * BLE listener is only armed while the camera screen is foregrounded.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface HybridCameraRemote
  extends HybridObject<{ android: 'kotlin' }> {
  /** Tells the band to enable its on-watch shutter affordance. */
  arm(): Promise<void>;
  /** Tells the band to retire the shutter affordance. */
  disarm(): Promise<void>;
  readonly armed: boolean;
  /** Fires when the band reports a shutter press. */
  onShutter(listener: () => void): () => void;
}
