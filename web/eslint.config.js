// ESLint: only the React hooks rules (rules-of-hooks, exhaustive-deps) over src and tests.
// Type checking stays with `tsc -b`; formatting has no linter here. Intentional dependency
// omissions carry an explicit, commented `eslint-disable-next-line react-hooks/exhaustive-deps`.
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default [
  { ignores: ['dist/**', 'node_modules/**', 'playwright-report/**', 'test-results/**'] },
  {
    files: ['src/**/*.{ts,tsx}', 'tests/**/*.{ts,tsx}'],
    languageOptions: { parser: tseslint.parser },
    plugins: { 'react-hooks': reactHooks },
    linterOptions: { reportUnusedDisableDirectives: 'error' },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
    },
  },
];
