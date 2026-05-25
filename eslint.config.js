const expoConfig = require('eslint-config-expo/flat');

module.exports = [
  ...expoConfig,
  {
    ignores: [
      'node_modules/**',
      'Gadgetbridge/**',
      'android/**',
      'ios/**',
      'dist/**',
      '.expo/**',
      'nitrogen/generated/**',
    ],
  },
  {
    files: ['**/*.{ts,tsx}'],
    rules: {
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.name='requireNativeModule']",
          message:
            'Use Nitro HybridObject via modules/native/*, not requireNativeModule. See CLAUDE.md "Native bridge".',
        },
        {
          selector: "Literal[value=/^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/]",
          message:
            'Hex color literals are forbidden outside ThemeContext/contrast.ts. Use theme.* from useTheme(). See CLAUDE.md rule 4.',
        },
      ],
      'react-hooks/exhaustive-deps': 'error',
    },
  },
  {
    files: [
      'context/ThemeContext.tsx',
      'components/themed/contrast.ts',
      'constants/DesignSystem.ts',
      'components/brand/**',
    ],
    rules: {
      'no-restricted-syntax': 'off',
    },
  },
  {
    files: ['**/*.config.{js,cjs,mjs}', '**/__tests__/**'],
    rules: {
      'no-restricted-syntax': 'off',
      'no-console': 'off',
    },
  },
];
