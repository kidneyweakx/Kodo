// Copyright (C) 2026 kidneyweakx
// AGPL-3.0-or-later
//
// Expo config plugin. After `expo prebuild --platform android` writes the
// android/ tree, this plugin:
//   1. Adds an extra source set so `android-port/src/main/{java,proto}` is
//      compiled into the same APK.
//   2. Wires in the dependencies our Kotlin port needs (bouncycastle,
//      protobuf-javalite, kotlinx coroutines, androidx healthconnect, etc).
//
// We keep the port outside `android/` so `expo prebuild --clean` does not
// nuke it.

const { withAppBuildGradle } = require('@expo/config-plugins');

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

function injectBlock(source, anchor, block, alreadyContains) {
  if (source.includes(alreadyContains)) return source;
  const idx = source.indexOf(anchor);
  if (idx === -1) {
    throw new Error(`with-android-port: anchor not found: ${anchor}`);
  }
  const insertAt = idx + anchor.length;
  return source.slice(0, insertAt) + '\n' + block + '\n' + source.slice(insertAt);
}

const withAndroidPort = (config) => {
  return withAppBuildGradle(config, (cfg) => {
    let src = cfg.modResults.contents;

    // 1. Apply protobuf plugin at the top of the file.
    src = injectBlock(
      src,
      `apply plugin: "com.facebook.react"`,
      EXTRA_PROTOBUF_BLOCK,
      "apply plugin: 'com.google.protobuf'",
    );

    // 2. Add source set + dependencies inside the `android { ... }` block.
    src = injectBlock(
      src,
      'android {',
      EXTRA_SOURCE_SETS,
      "rootProject.file('../android-port/src/main/java')",
    );

    // 3. Append our dependencies right before the closing `dependencies { ... }` brace.
    if (!src.includes("bcprov-jdk18on")) {
      const depsAnchor = 'dependencies {';
      const i = src.indexOf(depsAnchor);
      if (i === -1) throw new Error("with-android-port: dependencies block not found");
      src = injectBlock(src, depsAnchor, EXTRA_DEPENDENCIES, 'bcprov-jdk18on');
    }

    cfg.modResults.contents = src;
    return cfg;
  });
};

module.exports = withAndroidPort;
