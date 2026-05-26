// Copyright (C) 2026 kidneyweakx
// AGPL-3.0-or-later
//
// Expo config plugin. After `expo prebuild --platform android` writes the
// android/ tree, this plugin:
//   1. Adds an extra source set so `android-port/src/main/{java,proto}` is
//      compiled into the same APK, plus the Nitrogen-generated Kotlin glue.
//   2. Pulls in dependencies our Kotlin port needs (bouncycastle, protobuf-
//      javalite, kotlinx coroutines, androidx healthconnect, etc).
//   3. Applies Nitrogen's `+autolinking.gradle` so the Nitro C++ lib config
//      is in scope.
//   4. Wires `externalNativeBuild { cmake { path '../CMakeLists.txt' } }` and
//      `buildFeatures { prefab true }` so libMiBand9ActiveNitro.so actually
//      gets built and findable via prefab.
//   5. Drops a top-level `android/CMakeLists.txt` if missing.
//   6. Patches MainApplication.kt to call MiBand9ActiveNitroOnLoad.initializeNative()
//      before `loadReactNative(...)`.
//
// We keep the port outside `android/` so `expo prebuild --clean` does not
// nuke it.

const fs = require('fs');
const path = require('path');

const {
  withAppBuildGradle,
  withDangerousMod,
  withMainApplication,
} = require('@expo/config-plugins');

const EXTRA_SOURCE_SETS = `
    sourceSets {
        main {
            java.srcDirs += rootProject.file('../android-port/src/main/java')
            java.srcDirs += rootProject.file('../nitrogen/generated/android/kotlin')
            proto.srcDirs += rootProject.file('../android-port/src/main/proto')
        }
    }
`;

const EXTRA_DEPENDENCIES = `
    // mi-band-9-active port dependencies
    implementation 'org.bouncycastle:bcprov-jdk18on:1.78.1'
    implementation 'com.google.protobuf:protobuf-javalite:3.25.5'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0'
    implementation 'androidx.health.connect:connect-client:1.1.0-rc02'
    implementation 'androidx.work:work-runtime-ktx:2.10.0'
`;

const EXTRA_PROTOBUF_BLOCK = `
apply plugin: 'com.google.protobuf'

protobuf {
    protoc {
        artifact = 'com.google.protobuf:protoc:3.25.5'
    }
    generateProtoTasks {
        all().each { task ->
            task.builtins {
                java {
                    option 'lite'
                }
            }
        }
    }
}
`;

const NITRO_AUTOLINK_APPLY = `
// Nitro: pull in Nitrogen-generated Kotlin source set + native lib config.
apply from: '../../nitrogen/generated/android/MiBand9ActiveNitro+autolinking.gradle'
`;

const NITRO_DEFAULT_CONFIG_NATIVE = `
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++20"
                arguments "-DANDROID_STL=c++_shared"
            }
        }
`;

const NITRO_EXTERNAL_NATIVE_BUILD = `
    // Nitro JNI sources are compiled into libMiBand9ActiveNitro.so via CMake.
    externalNativeBuild {
        cmake {
            path '../CMakeLists.txt'
        }
    }

    buildFeatures {
        prefab true
    }
`;

const TOP_LEVEL_CMAKE = `# mi-band-9-active — app-level CMake.
# Copyright (C) 2026 kidneyweakx
# AGPL-3.0-or-later. See LICENSE, NOTICE.md.
#
# (1) project(appmodules) + ReactNative-application.cmake → libappmodules.so
#     (contains PlatformConstants and all autolinked TurboModules).
# (2) Build libMiBand9ActiveNitro.so for our in-repo Nitro module alongside.

cmake_minimum_required(VERSION 3.13)
project(appmodules)

include(\${REACT_ANDROID_DIR}/cmake-utils/ReactNative-application.cmake)

add_library(MiBand9ActiveNitro SHARED
    \${CMAKE_CURRENT_SOURCE_DIR}/nitro/cpp-adapter.cpp
)
include(\${CMAKE_SOURCE_DIR}/../nitrogen/generated/android/MiBand9ActiveNitro+autolinking.cmake)
`;

const CPP_ADAPTER = `// mi-band-9-active — JNI_OnLoad for the MiBand9ActiveNitro native lib.
// Copyright (C) 2026 kidneyweakx
// AGPL-3.0-or-later. See LICENSE, NOTICE.md.
//
// Without this, \`System.loadLibrary("MiBand9ActiveNitro")\` loads the .so but
// Nitrogen's \`registerAllNatives()\` is never invoked, so no HybridObject
// constructors get registered with the registry and every JS call to
// \`NitroModules.createHybridObject(...)\` throws "not registered".

#include <fbjni/fbjni.h>
#include <jni.h>

#include "MiBand9ActiveNitroOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::miband9active::registerAllNatives();
  });
}
`;

function injectBlock(source, anchor, block, alreadyContains) {
  if (source.includes(alreadyContains)) return source;
  const idx = source.indexOf(anchor);
  if (idx === -1) {
    throw new Error(`with-android-port: anchor not found: ${anchor}`);
  }
  const insertAt = idx + anchor.length;
  return source.slice(0, insertAt) + '\n' + block + '\n' + source.slice(insertAt);
}

const withAppGradle = (config) =>
  withAppBuildGradle(config, (cfg) => {
    let src = cfg.modResults.contents;

    // 1. Apply protobuf plugin at the top of the file.
    src = injectBlock(
      src,
      `apply plugin: "com.facebook.react"`,
      EXTRA_PROTOBUF_BLOCK,
      "apply plugin: 'com.google.protobuf'",
    );

    // 2. Apply Nitrogen autolinking.gradle right after protobuf.
    src = injectBlock(
      src,
      `apply plugin: "com.facebook.react"`,
      NITRO_AUTOLINK_APPLY,
      'MiBand9ActiveNitro+autolinking.gradle',
    );

    // 3. Add source set inside `android { ... }`.
    src = injectBlock(
      src,
      'android {',
      EXTRA_SOURCE_SETS,
      "rootProject.file('../android-port/src/main/java')",
    );

    // 4. Add C++ flags into defaultConfig.
    src = injectBlock(
      src,
      'defaultConfig {',
      NITRO_DEFAULT_CONFIG_NATIVE,
      '-DANDROID_STL=c++_shared',
    );

    // 5. Append externalNativeBuild + prefab feature inside `android { ... }`.
    src = injectBlock(
      src,
      'android {',
      NITRO_EXTERNAL_NATIVE_BUILD,
      "path '../CMakeLists.txt'",
    );

    // 6. Append our dependencies inside `dependencies { ... }`.
    if (!src.includes('bcprov-jdk18on')) {
      const depsAnchor = 'dependencies {';
      const i = src.indexOf(depsAnchor);
      if (i === -1) throw new Error('with-android-port: dependencies block not found');
      src = injectBlock(src, depsAnchor, EXTRA_DEPENDENCIES, 'bcprov-jdk18on');
    }

    cfg.modResults.contents = src;
    return cfg;
  });

const withTopLevelCmake = (config) =>
  withDangerousMod(config, [
    'android',
    async (cfg) => {
      const cmakePath = path.join(cfg.modRequest.platformProjectRoot, 'CMakeLists.txt');
      if (!fs.existsSync(cmakePath)) {
        fs.writeFileSync(cmakePath, TOP_LEVEL_CMAKE);
      }
      const adapterDir = path.join(cfg.modRequest.platformProjectRoot, 'nitro');
      const adapterPath = path.join(adapterDir, 'cpp-adapter.cpp');
      if (!fs.existsSync(adapterPath)) {
        fs.mkdirSync(adapterDir, { recursive: true });
        fs.writeFileSync(adapterPath, CPP_ADAPTER);
      }
      return cfg;
    },
  ]);

const withNitroOnLoad = (config) =>
  withMainApplication(config, (cfg) => {
    let src = cfg.modResults.contents;
    if (!src.includes('com.margelo.nitro.miband9active.MiBand9ActiveNitroOnLoad')) {
      src = src.replace(
        /^import expo\.modules\.ApplicationLifecycleDispatcher$/m,
        'import com.margelo.nitro.miband9active.MiBand9ActiveNitroOnLoad\n\nimport expo.modules.ApplicationLifecycleDispatcher',
      );
    }
    if (!src.includes('MiBand9ActiveNitroOnLoad.initializeNative()')) {
      // Insert the call right before `loadReactNative(this)`.
      src = src.replace(
        /(\s*)loadReactNative\(this\)/,
        '$1MiBand9ActiveNitroOnLoad.initializeNative()$1loadReactNative(this)',
      );
    }
    cfg.modResults.contents = src;
    return cfg;
  });

module.exports = (config) => {
  config = withAppGradle(config);
  config = withTopLevelCmake(config);
  config = withNitroOnLoad(config);
  return config;
};
