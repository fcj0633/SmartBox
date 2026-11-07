import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './live', testMatch: '*.spec.ts', workers:1, timeout:180000,
  reporter:[['line']], outputDir:'../../artifacts/task006/live-browser-results',
  use: { baseURL:'http://127.0.0.1:5173', viewport:{width:1440,height:900}, actionTimeout:20000, trace:'off', video:'off', screenshot:'off' },
});
