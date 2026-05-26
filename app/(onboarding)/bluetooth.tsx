/*
 * mi-band-9-active — onboarding step 3/8: bluetooth permission.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Behaviour:
 *   - If BT runtime perms are already granted on mount → skip to next step.
 *   - Otherwise CTA fires PermissionsAndroid.requestMultiple (real system
 *     dialog), then advances regardless of outcome so the user is never
 *     stuck on this page. The defensive re-check on scan.tsx blocks the
 *     actual scan if perms ended up denied.
 */

import { router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';

import { ThemedButton } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';
import { PermissionHero } from '@/components/onboarding/PermissionHero';

const NEXT = '/(onboarding)/notifications' as const;

export default function BluetoothScreen() {
  const [busy, setBusy] = useState(false);
  const advanced = useRef(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const granted = await permissions.hasBluetoothPermissions();
      if (cancelled || advanced.current) return;
      if (granted) {
        advanced.current = true;
        router.replace(NEXT);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const onRequest = async () => {
    if (busy || advanced.current) return;
    setBusy(true);
    try {
      await permissions.requestBluetooth();
      await permissions.promptEnableBluetooth();
    } finally {
      if (!advanced.current) {
        advanced.current = true;
        router.replace(NEXT);
      }
      setBusy(false);
    }
  };

  return (
    <OnboardingScaffold
      stepIndex={2}
      totalSteps={7}
      eyebrow="STEP 3"
      title={t('onboarding.bluetooth.title')}
      body={t('onboarding.bluetooth.body')}
      footer={
        <ThemedButton
          label={t('onboarding.bluetooth.cta')}
          size="lg"
          fullWidth
          loading={busy}
          onPress={onRequest}
        />
      }
    >
      <PermissionHero icon="bluetooth" />
    </OnboardingScaffold>
  );
}
