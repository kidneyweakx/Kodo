// Copyright (C) 2026 kidneyweakx
// AGPL-3.0-or-later
//
// Adds our ported components to AndroidManifest.xml:
//   - InitializerProvider (so AppContext gets a context pre-Application)
//   - MiBand9NotificationListener
//   - MiBand9GpsService (foreground location|connectedDevice)
//   - GenericWeatherReceiver (ACTION_GENERIC_WEATHER broadcast)

const { withAndroidManifest, AndroidConfig } = require('@expo/config-plugins');

const PROVIDER_NAME = 'com.kidneyweakx.miband9active.InitializerProvider';
const LISTENER_NAME = 'com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener';
const GPS_SERVICE = 'com.kidneyweakx.miband9active.gps.MiBand9GpsService';
const FIND_PHONE_STOP = 'com.kidneyweakx.miband9active.FindPhoneStopReceiver';
const WEATHER_RECEIVER = 'com.kidneyweakx.miband9active.xiaomi.services.GenericWeatherReceiver';

function ensureProvider(application, packageName) {
  application.provider = application.provider || [];
  if (!application.provider.find((p) => p.$['android:name'] === PROVIDER_NAME)) {
    application.provider.push({
      $: {
        'android:name': PROVIDER_NAME,
        'android:authorities': `${packageName}.initializer`,
        'android:exported': 'false',
      },
    });
  }
}

function ensureListener(application) {
  application.service = application.service || [];
  if (!application.service.find((s) => s.$['android:name'] === LISTENER_NAME)) {
    application.service.push({
      $: {
        'android:name': LISTENER_NAME,
        'android:exported': 'true',
        'android:label': '@string/app_name',
        'android:permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
      },
      'intent-filter': [{ action: [{ $: { 'android:name': 'android.service.notification.NotificationListenerService' } }] }],
    });
  }
}

function ensureGpsService(application) {
  application.service = application.service || [];
  if (!application.service.find((s) => s.$['android:name'] === GPS_SERVICE)) {
    application.service.push({
      $: {
        'android:name': GPS_SERVICE,
        'android:exported': 'false',
        'android:foregroundServiceType': 'location',
      },
    });
  }
}

function ensureWeatherReceiver(application) {
  application.receiver = application.receiver || [];
  if (!application.receiver.find((r) => r.$['android:name'] === WEATHER_RECEIVER)) {
    application.receiver.push({
      $: { 'android:name': WEATHER_RECEIVER, 'android:exported': 'true' },
      'intent-filter': [{
        action: [
          { $: { 'android:name': 'com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER' } },
          // Weather apps built for Gadgetbridge (Breezy, GBWeather…) target this action.
          { $: { 'android:name': 'nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER' } },
        ],
      }],
    });
  }
}

// "Found it" action on the find-phone notification.
function ensureFindPhoneReceiver(application) {
  application.receiver = application.receiver || [];
  if (!application.receiver.find((r) => r.$['android:name'] === FIND_PHONE_STOP)) {
    application.receiver.push({ $: { 'android:name': FIND_PHONE_STOP, 'android:exported': 'false' } });
  }
}

// Package visibility (API 30+): launcher apps for notification labels/icons,
// and the Health Connect provider app on Android 13 and below.
function ensureQueries(manifest) {
  manifest.queries = manifest.queries || [];
  const has = (pred) => manifest.queries.some(pred);
  if (!has((q) => (q.intent || []).some((i) => (i.category || []).some((c) => c.$['android:name'] === 'android.intent.category.LAUNCHER')))) {
    manifest.queries.push({
      intent: [{
        action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }],
        category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }],
      }],
    });
  }
  if (!has((q) => (q.package || []).some((pk) => pk.$['android:name'] === 'com.google.android.apps.healthdata'))) {
    manifest.queries.push({ package: [{ $: { 'android:name': 'com.google.android.apps.healthdata' } }] });
  }
}

// Health Connect requires both aliases or the permission sheet on Android 14+
// returns immediately with nothing granted.
function ensureHealthConnectAliases(application) {
  application['activity-alias'] = application['activity-alias'] || [];
  const aliases = application['activity-alias'];
  const add = (alias) => {
    if (!aliases.find((a) => a.$['android:name'] === alias.$['android:name'])) aliases.push(alias);
  };
  add({
    $: {
      'android:name': 'HealthConnectPermissionsRationale',
      'android:exported': 'true',
      'android:targetActivity': '.MainActivity',
    },
    'intent-filter': [{ action: [{ $: { 'android:name': 'androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE' } }] }],
  });
  add({
    $: {
      'android:name': 'ViewPermissionUsageActivity',
      'android:exported': 'true',
      'android:targetActivity': '.MainActivity',
      'android:permission': 'android.permission.START_VIEW_PERMISSION_USAGE',
    },
    'intent-filter': [{
      action: [{ $: { 'android:name': 'android.intent.action.VIEW_PERMISSION_USAGE' } }],
      category: [{ $: { 'android:name': 'android.intent.category.HEALTH_PERMISSIONS' } }],
    }],
  });
}

module.exports = (config) =>
  withAndroidManifest(config, async (cfg) => {
    const mainApp = AndroidConfig.Manifest.getMainApplicationOrThrow(cfg.modResults);
    const packageName = cfg.modResults.manifest.$.package || 'com.kidneyweakx.miband9active';
    ensureProvider(mainApp, packageName);
    ensureListener(mainApp);
    ensureGpsService(mainApp);
    ensureWeatherReceiver(mainApp);
    ensureFindPhoneReceiver(mainApp);
    ensureQueries(cfg.modResults.manifest);
    ensureHealthConnectAliases(mainApp);
    return cfg;
  });
