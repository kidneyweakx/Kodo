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
        'android:foregroundServiceType': 'location|connectedDevice',
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
        action: [{ $: { 'android:name': 'com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER' } }],
      }],
    });
  }
}

module.exports = (config) =>
  withAndroidManifest(config, async (cfg) => {
    const mainApp = AndroidConfig.Manifest.getMainApplicationOrThrow(cfg.modResults);
    const packageName = cfg.modResults.manifest.$.package || 'com.kidneyweakx.miband9active';
    ensureProvider(mainApp, packageName);
    ensureListener(mainApp);
    ensureGpsService(mainApp);
    ensureWeatherReceiver(mainApp);
    return cfg;
  });
