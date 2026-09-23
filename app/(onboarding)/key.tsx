/*
 * mi-band-9-active — onboarding 3/4: auth key + real pairing handshake.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Unlike the old flow (which only saved the key locally and let the first
 * sync discover a typo), "Pair" here runs the full GATT connect + encrypted
 * auth. The button label follows the live connection state so the user sees
 * connecting → verifying → paired, and failures map to a specific fix.
 */

import * as Clipboard from 'expo-clipboard';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Linking, Pressable, TextInput, View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { IconTile } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { bandLink, normalizeAuthKey, pairErrorCode, useConnectionState } from '@/libs/services/bandLink';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

const GUIDE_URL = 'https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/';

/** Hex digits the user has typed so far, ignoring 0x / separators. */
const hexDigits = (raw: string): number =>
  raw.trim().replace(/^0x/i, '').replace(/[^0-9a-f]/gi, '').length;

export default function KeyScreen() {
  const { theme } = useTheme();
  const params = useLocalSearchParams<{ deviceId?: string; name?: string }>();
  const deviceId = String(params.deviceId ?? '');
  const name = String(params.name ?? '');

  const [raw, setRaw] = useState('');
  const [pairing, setPairing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showHelp, setShowHelp] = useState(false);
  const [clipboardHasText, setClipboardHasText] = useState(false);
  const connection = useConnectionState();

  const normalized = useMemo(() => normalizeAuthKey(raw), [raw]);
  const digits = hexDigits(raw);

  // hasStringAsync doesn't read the clip, so Android shows no paste toast.
  useEffect(() => {
    void Clipboard.hasStringAsync().then(setClipboardHasText).catch(() => undefined);
  }, []);

  const onPaste = async () => {
    const text = await Clipboard.getStringAsync().catch(() => '');
    if (!text) return;
    void hapticsBridge.fire('selection');
    setRaw(text.trim());
    setError(null);
  };

  const onPair = async () => {
    if (!normalized || pairing) return;
    setPairing(true);
    setError(null);
    try {
      await bandLink.pair(deviceId, normalized, name || undefined);
      void hapticsBridge.fire('success');
      router.replace('/(onboarding)/extras');
    } catch (e) {
      console.warn('[key] pair failed', e);
      void hapticsBridge.fire('error');
      const code = pairErrorCode(e);
      setError(t(`onboarding.key.errors.${code}`));
      setPairing(false);
    }
  };

  const pairLabel = !pairing
    ? t('onboarding.key.cta')
    : connection === 'authenticating'
      ? t('onboarding.key.phase.authenticating')
      : connection === 'connected'
        ? t('onboarding.key.phase.connected')
        : t('onboarding.key.phase.connecting');

  if (!deviceId) {
    return (
      <OnboardingScaffold
        stepIndex={2}
        totalSteps={4}
        title={t('onboarding.key.title')}
        body={t('onboarding.key.errors.NOT_PAIRED')}
        footer={
          <ThemedButton
            label={t('onboarding.connect.title')}
            size="lg"
            fullWidth
            onPress={() => router.replace('/(onboarding)/connect')}
          />
        }
      />
    );
  }

  const borderColor = error ? theme.danger : normalized ? theme.accent : theme.border;

  return (
    <OnboardingScaffold
      stepIndex={2}
      totalSteps={4}
      title={t('onboarding.key.title')}
      body={t('onboarding.key.body')}
      footer={
        <ThemedButton
          label={pairLabel}
          size="lg"
          fullWidth
          loading={pairing}
          disabled={!normalized}
          onPress={onPair}
        />
      }
    >
      <View style={{ gap: Spacing.lg }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.md }}>
          <IconTile name="band" />
          <View style={{ flex: 1 }}>
            <ThemedText variant="titleMedium">{name || 'Mi Band 9 Active'}</ThemedText>
            <ThemedText variant="caption" tone="tertiary" style={tabularNums}>
              {deviceId}
            </ThemedText>
          </View>
        </View>

        <View
          style={{
            borderRadius: Radius.lg,
            borderWidth: 1.5,
            borderColor,
            paddingHorizontal: Spacing.lg,
            paddingVertical: Spacing.sm,
            backgroundColor: theme.background.secondary,
            gap: Spacing.xs,
          }}
        >
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.sm }}>
            <TextInput
              value={raw}
              onChangeText={(v) => {
                setRaw(v);
                setError(null);
              }}
              editable={!pairing}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="off"
              spellCheck={false}
              multiline
              placeholder={t('onboarding.key.placeholder')}
              placeholderTextColor={theme.text.tertiary}
              style={{
                flex: 1,
                color: theme.text.primary,
                fontFamily: 'monospace',
                fontSize: 15,
                paddingVertical: Spacing.sm,
              }}
            />
            <ThemedButton
              variant={clipboardHasText && !raw ? 'primary' : 'secondary'}
              size="sm"
              label={t('onboarding.key.paste')}
              onPress={onPaste}
              disabled={pairing}
            />
          </View>
          <ThemedText
            variant="caption"
            tone={normalized ? 'success' : digits > 32 ? 'error' : 'tertiary'}
            style={tabularNums}
          >
            {normalized ? `✓ ${t('onboarding.key.valid')}` : t('onboarding.key.count', { count: digits })}
          </ThemedText>
        </View>

        {error ? (
          <Animated.View entering={FadeIn.duration(200)}>
            <ThemedText variant="bodyMedium" tone="error">
              {error}
            </ThemedText>
          </Animated.View>
        ) : null}

        <Pressable onPress={() => setShowHelp((v) => !v)} hitSlop={8} accessibilityRole="button">
          <ThemedText variant="bodyMedium" tone="accent">
            {showHelp ? '▾' : '▸'} {t('onboarding.key.help')}
          </ThemedText>
        </Pressable>
        {showHelp ? (
          <ThemedSurface variant="outlined" padded="lg" style={{ gap: Spacing.sm }}>
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('onboarding.key.helpBody')}
            </ThemedText>
            <Pressable onPress={() => void Linking.openURL(GUIDE_URL)} hitSlop={8}>
              <ThemedText variant="caption" tone="accent">
                {t('onboarding.key.helpLink')} →
              </ThemedText>
            </Pressable>
          </ThemedSurface>
        ) : null}
      </View>
    </OnboardingScaffold>
  );
}
