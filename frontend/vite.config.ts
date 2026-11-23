import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
const apiTarget = process.env.SMARTBOX_API_TARGET ?? 'http://127.0.0.1:8080';
const port = Number(process.env.SMARTBOX_WEB_PORT ?? 5173);
export default defineConfig({
  plugins: [vue()],
  server: { host: '127.0.0.1', port, strictPort: true, proxy: {
    '/api': { target: apiTarget, changeOrigin: false },
    '/ws': { target: apiTarget.replace(/^http/, 'ws'), ws: true, changeOrigin: false },
  } },
});
