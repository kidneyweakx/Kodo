// Copyright (C) 2026 kidneyweakx
// AGPL-3.0-or-later
//
// Adds the protobuf-gradle-plugin classpath to the top-level Android
// build.gradle so the app module's `apply plugin: 'com.google.protobuf'`
// resolves.

const { withProjectBuildGradle } = require('@expo/config-plugins');

const PROTOBUF_CLASSPATH = `        classpath 'com.google.protobuf:protobuf-gradle-plugin:0.9.4'`;

module.exports = (config) =>
  withProjectBuildGradle(config, (cfg) => {
    let src = cfg.modResults.contents;
    if (src.includes('protobuf-gradle-plugin')) return cfg;

    // Insert under the buildscript { dependencies { ... } } block.
    src = src.replace(
      /(buildscript\s*\{[\s\S]*?dependencies\s*\{)/,
      (m) => `${m}\n${PROTOBUF_CLASSPATH}`,
    );

    cfg.modResults.contents = src;
    return cfg;
  });
