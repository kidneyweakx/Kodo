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
 * JS facade over HybridWatchface. `uri` may be a content:// URI from the
 * document picker, a file:// URI or an absolute path.
 */

import { NativeWatchface } from '@/modules/native';
import { safeCall, safeUnsubscribe } from '@/modules/native/safe';
import type { WatchfaceFileInfo, WatchfaceInfo } from '@/modules/native';

export const watchface = {
  getCachedList(): readonly WatchfaceInfo[] {
    return safeCall<readonly WatchfaceInfo[]>(() => NativeWatchface().getCachedList(), []);
  },
  list(): Promise<readonly WatchfaceInfo[]> {
    return NativeWatchface().list();
  },
  inspect(uri: string): Promise<WatchfaceFileInfo> {
    return NativeWatchface().inspect(uri);
  },
  install(uri: string): Promise<WatchfaceFileInfo> {
    return NativeWatchface().install(uri);
  },
  setActive(id: string): Promise<void> {
    return NativeWatchface().setActive(id);
  },
  remove(id: string): Promise<void> {
    return NativeWatchface().remove(id);
  },
  onInstallProgress(listener: (percent: number) => void): () => void {
    return safeUnsubscribe(() => NativeWatchface().onInstallProgress(listener));
  },
};
