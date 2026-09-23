#!/usr/bin/env node
/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
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
 */

// Stamps app.json + package.json with a release version before prebuild.
//
//   node scripts/set-version.js v1.2.3      → version 1.2.3, versionCode 10203
//   node scripts/set-version.js 1.2.3-rc.1  → version 1.2.3-rc.1, versionCode 10203
//
// versionCode = major*10000 + minor*100 + patch, so it only ever grows as
// long as minor/patch stay below 100.

const fs = require('fs');
const path = require('path');

const raw = process.argv[2];
if (!raw) {
  console.error('usage: set-version.js <vX.Y.Z>');
  process.exit(1);
}

const version = raw.replace(/^v/, '');
const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(version);
if (!match) {
  console.error(`set-version: "${raw}" is not semver (expected vX.Y.Z)`);
  process.exit(1);
}
const [major, minor, patch] = match.slice(1).map(Number);
if (minor > 99 || patch > 99) {
  console.error('set-version: minor/patch must be < 100 to keep versionCode monotonic');
  process.exit(1);
}
const versionCode = major * 10000 + minor * 100 + patch;

const root = path.join(__dirname, '..');
const writeJson = (file, mutate) => {
  const p = path.join(root, file);
  const json = JSON.parse(fs.readFileSync(p, 'utf8'));
  mutate(json);
  fs.writeFileSync(p, `${JSON.stringify(json, null, 2)}\n`);
};

writeJson('app.json', (json) => {
  json.expo.version = version;
  json.expo.android.versionCode = Math.max(versionCode, 1);
});
writeJson('package.json', (json) => {
  json.version = version;
});

console.log(`version=${version} versionCode=${versionCode}`);
