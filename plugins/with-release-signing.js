// Copyright (C) 2026 kidneyweakx
// AGPL-3.0-or-later
//
// Expo config plugin. Gives `android/app/build.gradle` a real `release`
// signingConfig that reads the upload keystore from environment variables,
// so CI (and local release builds) never need a committed keystore:
//
//   KODO_KEYSTORE_PATH       absolute path to the .jks / .keystore
//   KODO_KEYSTORE_PASSWORD   store password
//   KODO_KEY_ALIAS           key alias
//   KODO_KEY_PASSWORD        key password (defaults to the store password)
//
// When KODO_KEYSTORE_PATH is unset the release build falls back to the debug
// keystore (what the Expo template does by default) so `assembleRelease`
// still produces an installable APK — just not one you can upgrade in place
// from a properly signed build.

const { withAppBuildGradle } = require('@expo/config-plugins');

const MARKER = '// kodo: release signing';

const SIGNING_CONFIG = `
        ${MARKER}
        release {
            def ksPath = System.getenv("KODO_KEYSTORE_PATH")
            if (ksPath) {
                storeFile file(ksPath)
                storePassword System.getenv("KODO_KEYSTORE_PASSWORD")
                keyAlias System.getenv("KODO_KEY_ALIAS")
                keyPassword System.getenv("KODO_KEY_PASSWORD") ?: System.getenv("KODO_KEYSTORE_PASSWORD")
            } else {
                storeFile file('debug.keystore')
                storePassword 'android'
                keyAlias 'androiddebugkey'
                keyPassword 'android'
            }
        }`;

module.exports = (config) =>
  withAppBuildGradle(config, (cfg) => {
    let src = cfg.modResults.contents;
    if (src.includes(MARKER)) return cfg;

    // 1. Declare signingConfigs.release next to the template's debug config.
    const signingAnchor = /signingConfigs\s*\{/;
    if (!signingAnchor.test(src)) {
      throw new Error('with-release-signing: signingConfigs block not found');
    }
    src = src.replace(signingAnchor, (m) => `${m}${SIGNING_CONFIG}`);

    // 2. Point buildTypes.release at it (the template signs release with debug).
    const releaseBlock = /(buildTypes\s*\{[\s\S]*?release\s*\{[\s\S]*?)signingConfig\s+signingConfigs\.debug/;
    if (!releaseBlock.test(src)) {
      throw new Error('with-release-signing: buildTypes.release signingConfig not found');
    }
    src = src.replace(releaseBlock, '$1signingConfig signingConfigs.release');

    cfg.modResults.contents = src;
    return cfg;
  });
