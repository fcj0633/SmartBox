import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './browser', testMatch: '*.spec.ts', workers: 1, timeout: 35000,
  reporter: [['line'], ['json', { outputFile: process.env.SMARTBOX_BROWSER_REPORT || '../../artifacts/task006/isolated-report.json' }]], outputDir: process.env.SMARTBOX_BROWSER_OUTPUT_DIR || '../../artifacts/task006/browser-results',
  use: { baseURL: process.env.SMARTBOX_TEST_BASE_URL || 'http://127.0.0.1:5173', viewport: { width:1440,height:900 }, trace:'off', video:'off', screenshot:'off' },
});
