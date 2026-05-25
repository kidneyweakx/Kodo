/*
 * mi-band-9-active — safe-mode helpers wrapping NativeBandLink etc.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Until the Kotlin Nitro implementations are authored, any call into a native
 * HybridObject can throw (createHybridObject doesn't find the registration).
 * The facades in libs/services/* use these helpers so the JS shell stays
 * launchable for design-iteration. Real BLE/Notification flows light up
 * automatically once HybridBandLink.kt etc. are dropped in.
 */

export const safeCall = <T>(fn: () => T, fallback: T): T => {
  try {
    return fn();
  } catch {
    return fallback;
  }
};

export const safeAsync = async <T>(fn: () => Promise<T>, fallback: T): Promise<T> => {
  try {
    return await fn();
  } catch {
    return fallback;
  }
};

export const safeUnsubscribe = (fn: () => () => void): (() => void) => {
  try {
    return fn();
  } catch {
    return () => {};
  }
};
