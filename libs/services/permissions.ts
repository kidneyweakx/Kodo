/*
 * mi-band-9-active — OS permission + adapter/services prompts.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Mirrors Gadgetbridge's DiscoveryActivityV2.checkAndRequestLocationPermission +
 * ensureBluetoothReady so a fresh install can actually reach a working scan.
 * Nitro owns the *use* of the granted permissions (GATT, listener service, etc.);
 * asking the OS is a plain RN call so we don't block onboarding on Kotlin codegen.
 */

import { Linking, PermissionsAndroid, Platform } from 'react-native';
import type { Permission } from 'react-native';

type PermissionId = keyof typeof PermissionsAndroid.PERMISSIONS;

const BT_RUNTIME_PERMISSIONS: PermissionId[] = [
  'BLUETOOTH_CONNECT',
  'BLUETOOTH_SCAN',
  'ACCESS_FINE_LOCATION',
  'ACCESS_COARSE_LOCATION',
];

const isAndroid = Platform.OS === 'android';

const resolvePermission = (id: PermissionId): Permission | null => {
  const value = PermissionsAndroid.PERMISSIONS[id];
  return typeof value === 'string' ? (value as Permission) : null;
};

export interface BluetoothReadiness {
  readonly permissionsGranted: boolean;
  readonly missing: readonly string[];
}

export const permissions = {
  async requestBluetooth(): Promise<BluetoothReadiness> {
    if (!isAndroid) return { permissionsGranted: true, missing: [] };
    const wanted = BT_RUNTIME_PERMISSIONS.map((id) => ({ id, value: resolvePermission(id) }))
      .filter((p): p is { id: PermissionId; value: Permission } => p.value !== null);
    if (wanted.length === 0) return { permissionsGranted: true, missing: [] };
    try {
      const result = await PermissionsAndroid.requestMultiple(wanted.map((w) => w.value));
      const missing = wanted
        .filter((w) => result[w.value] !== PermissionsAndroid.RESULTS.GRANTED)
        .map((w) => w.id);
      // BLUETOOTH_CONNECT + BLUETOOTH_SCAN on S+ are the hard requirements.
      // FINE_LOCATION is required on Android <12 for BLE scan to surface results.
      const blocking = missing.filter((id) =>
        id === 'BLUETOOTH_CONNECT' || id === 'BLUETOOTH_SCAN' || id === 'ACCESS_FINE_LOCATION',
      );
      return { permissionsGranted: blocking.length === 0, missing };
    } catch {
      return { permissionsGranted: false, missing: BT_RUNTIME_PERMISSIONS };
    }
  },

  async hasBluetoothPermissions(): Promise<boolean> {
    if (!isAndroid) return true;
    for (const id of BT_RUNTIME_PERMISSIONS) {
      const value = resolvePermission(id);
      if (!value) continue;
      // FINE only matters as a fallback; COARSE is opportunistic.
      if (id === 'ACCESS_COARSE_LOCATION') continue;
      const granted = await PermissionsAndroid.check(value);
      if (!granted) return false;
    }
    return true;
  },

  /**
   * Asks the OS to enable Bluetooth. If the adapter is already on, the system
   * dismisses the dialog immediately. Mirrors Gadgetbridge's
   * BluetoothAdapter.ACTION_REQUEST_ENABLE.
   */
  async promptEnableBluetooth(): Promise<void> {
    if (!isAndroid) return;
    try {
      await Linking.sendIntent('android.bluetooth.adapter.action.REQUEST_ENABLE');
    } catch {
      try {
        await Linking.sendIntent('android.settings.BLUETOOTH_SETTINGS');
      } catch {
        await Linking.openSettings();
      }
    }
  },

  /**
   * Location services must be ON at the OS level (GPS or NETWORK provider) for
   * BLE scan to surface results on Android 6+. We can't read the toggle from JS,
   * so we open the location settings page and let the user confirm.
   */
  async openLocationServicesSettings(): Promise<void> {
    if (!isAndroid) return;
    try {
      await Linking.sendIntent('android.settings.LOCATION_SOURCE_SETTINGS');
    } catch {
      await Linking.openSettings();
    }
  },

  /**
   * Phone permissions behind band call alerts: READ_PHONE_STATE (caller
   * number), ANSWER_PHONE_CALLS (reject from the band), READ_CONTACTS
   * (caller name). Only the first is required; the rest degrade gracefully.
   */
  async requestCallAlerts(): Promise<boolean> {
    if (!isAndroid) return true;
    const wanted = (['READ_PHONE_STATE', 'ANSWER_PHONE_CALLS', 'READ_CONTACTS'] as const)
      .map(resolvePermission)
      .filter((p): p is Permission => p !== null);
    try {
      const result = await PermissionsAndroid.requestMultiple(wanted);
      const phone = resolvePermission('READ_PHONE_STATE');
      return phone ? result[phone] === PermissionsAndroid.RESULTS.GRANTED : true;
    } catch {
      return false;
    }
  },

  async hasCallAlerts(): Promise<boolean> {
    if (!isAndroid) return true;
    const phone = resolvePermission('READ_PHONE_STATE');
    if (!phone) return true;
    try {
      return await PermissionsAndroid.check(phone);
    } catch {
      return false;
    }
  },

  async hasPostNotifications(): Promise<boolean> {
    if (!isAndroid) return true;
    const id = resolvePermission('POST_NOTIFICATIONS');
    if (!id) return true;
    try {
      return await PermissionsAndroid.check(id);
    } catch {
      return false;
    }
  },

  async requestPostNotifications(): Promise<boolean> {
    if (!isAndroid) return true;
    const id = resolvePermission('POST_NOTIFICATIONS');
    if (!id) return true;
    try {
      const result = await PermissionsAndroid.request(id);
      return result === PermissionsAndroid.RESULTS.GRANTED;
    } catch {
      return false;
    }
  },

  async openNotificationListenerSettings(): Promise<void> {
    if (!isAndroid) return;
    try {
      await Linking.sendIntent('android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS');
    } catch {
      await Linking.openSettings();
    }
  },

  async openBatteryOptimizationSettings(): Promise<void> {
    if (!isAndroid) return;
    try {
      await Linking.sendIntent('android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS');
    } catch {
      await Linking.openSettings();
    }
  },
};
