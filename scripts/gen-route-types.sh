#!/usr/bin/env bash
# mi-band-9-active — generate Expo Router typed-route declarations
# (.expo/types/router.d.ts) for `tsc` in CI. Expo only writes them while the
# dev server boots, so start it offline, wait for the file, then stop it.
# Copyright (C) 2026 kidneyweakx — AGPL-3.0-or-later
set -euo pipefail
out=.expo/types/router.d.ts
rm -f "$out"
CI=1 bunx expo start --offline --port 8099 >/tmp/expo-typegen.log 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  [ -s "$out" ] && break
  sleep 2
done
kill "$pid" 2>/dev/null || true
pkill -f "expo start --offline --port 8099" 2>/dev/null || true
if [ ! -s "$out" ]; then
  cat /tmp/expo-typegen.log
  echo "typed routes were not generated" >&2
  exit 1
fi
echo "generated $out"
