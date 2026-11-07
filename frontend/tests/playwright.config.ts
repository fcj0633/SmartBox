import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './browser', testMatch: '*.spec.ts', workers: 1, timeout: 35000,
  reporter: [['line'], ['json', { outputFile: '../../artifacts/task006/isolated-report.json' }]], outputDir: '../../artifacts/task006/browser-results',
  use: { baseURL: 'http://127.0.0.1:5173', viewport: { width:1440,height:900 }, trace:'off', video:'off', screenshot:'off' },
});
