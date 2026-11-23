/* Real cloud stack only. No route mocks, HAR, trace, or credential screenshots. */
const { chromium } = require('playwright-core');
const fs = require('node:fs/promises');
const path = require('node:path');
(async () => {
  const input = JSON.parse(await fs.readFile(process.argv[2], 'utf8'));
  const browser = await chromium.launch({ headless: true, args: ['--no-proxy-server'] });
  const results = [];
  const operations = [];
  try {
    for (const user of input.users) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      const page = await context.newPage();
      let snapshots = 0;
      page.on('websocket', ws => ws.on('framereceived', frame => {
        try { if (JSON.parse(String(frame.payload)).type === 'snapshot') snapshots++; } catch {}
      }));
      await page.goto(input.url + '/login');
      await page.locator('#username').fill(user.username);
      await page.locator('#password').fill(user.password);
      await page.locator('button[type=submit], .login-submit').click();
      await page.waitForURL('**/boxes', { timeout: 15000 });
      const latest = page.waitForResponse(r => r.url().includes('/BOX-0001/latest') && r.status() === 200);
      await page.locator('a[href="/boxes/BOX-0001"]').click();
      const data = (await (await latest).json()).data;
      if (data.online_mask !== (input.expectedMask ?? 3)) throw Error('Unexpected real cloud node online mask');
      await page.getByText('实时通道已连接', { exact: true }).waitFor({ timeout: 15000 });
      const until = Date.now() + 10000;
      while (!snapshots && Date.now() < until) await page.waitForTimeout(100);
      if (!snapshots) throw Error('No real WS snapshot');
      if (user.role === 'DELIVERY_USER' && ['pose', 'sentry', 'evidence', 'node_diagnostics'].some(k => k in data)) throw Error('Forbidden role projection');
      if (user.role === 'LOGISTICS_OPERATOR' && !input.skipControl) {
        await page.locator('#command-kind').selectOption('output');
        await page.locator('#output-kind').selectOption(input.outputKind ?? 'light');
        await page.locator('#duration').fill(String(input.duration_s ?? 2));
        await page.getByRole('button', { name: '检查并确认操作', exact: true }).click();
        const accepted = page.waitForResponse(r => r.url().endsWith('/commands') && r.request().method() === 'POST' && r.status() === 202);
        await page.getByRole('button', { name: '确认发送', exact: true }).click();
        operations.push({ kind: input.outputKind ?? 'light', operation_id: (await (await accepted).json()).data.operation_id });
        await page.getByTestId('operation').getByText('成功', { exact: true }).first().waitFor({ timeout: 20000 });
        await page.screenshot({ path: path.join(input.directory, 'cloud-operator.png'), fullPage: true });
      }
      results.push({ role: user.role, online_mask: data.online_mask, websocket_snapshot: true, passed: true });
      await context.close();
    }
    await fs.writeFile(path.join(input.directory, 'browser-summary.json'), JSON.stringify({ results, operations, passed: true }, null, 2));
    console.log('PASS: ' + results.length + '-role real cloud browser/WS' + (input.skipControl ? ' (read-only)' : ' and output control'));
  } finally { await browser.close(); }
})().catch(() => { console.error('Cloud browser check failed; credentials suppressed.'); process.exitCode = 1; });
