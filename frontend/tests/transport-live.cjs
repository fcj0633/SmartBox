/* Real TASK-013 browser flow. Never record credentials, tokens, HAR or traces. */
const {
  chromium
} = require('playwright-core');
const fs = require('node:fs/promises');
const path = require('node:path');
(async () => {
  const input = JSON.parse(await fs.readFile(process.argv[2], 'utf8')),
    browser = await chromium.launch({
      headless: true,
      args: ['--no-proxy-server']
    });
  const results = [];
  const save = async (name, value) => fs.writeFile(path.join(input.directory, name + '.json'), JSON.stringify(value,
    null, 2));
  let step = 'launch';
  const assert = (value, message) => {
    if (!value) throw Error(message);
  };
  async function safeResponse(page, part, name) {
    const response = await page.waitForResponse(r => r.url().includes(part) && r.status() === 200);
    const value = await response.json();
    await save('safe-' + name, value);
    return value;
  }
  async function login(page, user) {
    await page.goto(input.url + '/login');
    await page.locator('#username').fill(user.username);
    await page.locator('#password').fill(user.password);
    await page.locator('.login-submit').click();
    await page.waitForURL(/\/(enterprise|rider)$/);
  }
  async function fits(page) {
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      'horizontal overflow');
  }
  try {
    const company = await browser.newContext({
        viewport: {
          width: 1440,
          height: 900
        }
      }),
      riding = await browser.newContext({
        viewport: {
          width: 390,
          height: 844
        }
      }),
      guest = await browser.newContext({
        viewport: {
          width: 390,
          height: 844
        }
      });
    const enterprise = await company.newPage(),
      rider = await riding.newPage(),
      viewer = await guest.newPage();
    step = 'enterprise login/candidates';
    const candidates = safeResponse(enterprise, '/enterprise/riders', 'riders');
    await login(enterprise, input.users[0]);
    await candidates;
    await enterprise.getByLabel('选择骑手 BOX-0001', {
      exact: true
    }).selectOption({
      label: input.users[1].username
    });
    await enterprise.getByRole('button', {
      name: '分配箱体',
      exact: true
    }).click();
    await enterprise.getByText('使用骑手：' + input.users[1].username, {
      exact: true
    }).waitFor();
    await fits(enterprise);
    results.push('enterprise assignment via real candidate API');
    step = 'rider create/item';
    await login(rider, input.users[1]);
    await rider.getByRole('link', {
      name: '开始新运输',
      exact: true
    }).click();
    await rider.getByRole('button').filter({
      hasText: 'BOX-0001'
    }).click();
    await rider.getByRole('button', {
      name: '下一步：绑定物品',
      exact: true
    }).click();
    await rider.waitForURL(/\/rider\/transports\/\d+/);
    const id = new URL(rider.url()).pathname.split('/').pop();
    await rider.getByLabel('物品名称', {
      exact: true
    }).fill('三端联调书籍');
    await rider.getByLabel('物品说明（可选）').fill('独立模拟节点，经真实MQTT上报');
    await rider.getByRole('button', {
      name: '保存物品并核对',
      exact: true
    }).click();
    await rider.getByRole('heading', {
      name: '核对并开始运输',
      exact: true
    }).waitFor();
    step = 'start/risk';
    await rider.getByRole('button', {
      name: '确认并开始运输',
      exact: true
    }).click();
    await rider.getByRole('dialog').getByRole('button', {
      name: '开始运输',
      exact: true
    }).click();
    await rider.getByRole('button', {
      name: '我已知晓风险，继续开始',
      exact: true
    }).waitFor();
    await rider.getByRole('button', {
      name: '我已知晓风险，继续开始',
      exact: true
    }).click();
    await rider.getByText('运输中', {
      exact: true
    }).waitFor();
    await save('active', {
      task_id: id
    });
    step = 'actual telemetry/event';
    await rider.getByRole('button', {
      name: '设备事件',
      exact: true
    }).click();
    await rider.getByText('设备故障', {
      exact: true
    }).waitFor({
      timeout: 35000
    });
    await rider.getByRole('button', {
      name: '返回运输概览',
      exact: true
    }).click();
    await fits(rider);
    await rider.screenshot({
      path: path.join(input.directory, 'rider-active.png'),
      fullPage: true
    });
    results.push('rider start and real MQTT sample/event displayed');
    await rider.getByRole('button', {
      name: '生成查看验证码',
      exact: true
    }).click();
    const code = await rider.locator('[aria-label="运输验证码"]').innerText();
    const no = await rider.locator('.transport-header .task-number').innerText();
    assert(/^[A-Za-z0-9]{10}$/.test(code), 'code format');
    step = 'anonymous invalid/correct verification';
    await viewer.goto(input.url + '/track');
    assert(viewer.url().endsWith('/track'), 'public guard');
    await viewer.getByLabel('运输编号', {
      exact: true
    }).fill(no);
    await viewer.getByLabel('查看验证码', {
      exact: true
    }).fill('WrongCode1');
    await viewer.getByRole('button', {
      name: '验证并查看',
      exact: true
    }).click();
    await viewer.getByRole('alert').waitFor();
    await viewer.getByLabel('查看验证码', {
      exact: true
    }).fill(code);
    const monitoring = safeResponse(viewer, '/public/transport-view', 'monitoring');
    await viewer.getByRole('button', {
      name: '验证并查看',
      exact: true
    }).click();
    const view = (await monitoring).data;
    assert(String(view.task.task_id) === id && view.samples.length > 0 && view.events.length > 0,
      'task data missing');
    assert(!('box_id' in view.task), 'guest identity leak');
    await viewer.getByRole('heading', {
      name: '三端联调书籍',
      exact: true
    }).waitFor();
    await fits(viewer);
    await viewer.screenshot({
      path: path.join(input.directory, 'guest-active.png'),
      fullPage: true
    });
    results.push('anonymous wrong code denied; verified single-task view via real HTTP');
    step = 'finish and three-role refresh';
    await rider.getByRole('button', {
      name: '隐藏验证码',
      exact: true
    }).click();
    await rider.getByRole('button', {
      name: '结束运输',
      exact: true
    }).click();
    await rider.getByRole('dialog').getByRole('button', {
      name: '结束运输',
      exact: true
    }).click();
    await rider.getByText('已结束', {
      exact: true
    }).waitFor();
    await viewer.getByRole('button', {
      name: '刷新',
      exact: true
    }).click();
    await viewer.getByText('已结束', {
      exact: true
    }).waitFor();
    const list = safeResponse(enterprise, '/enterprise/transports', 'enterprise');
    await enterprise.getByRole('button', {
      name: '刷新',
      exact: true
    }).click();
    await list;
    await enterprise.getByRole('button', {
      name: '刷新',
      exact: true
    }).click();
    await enterprise.locator('.route-task-row').filter({
      hasText: no
    }).click();
    await enterprise.getByRole('link', {
      name: '查看选中任务详情 ›',
      exact: true
    }).click();
    await enterprise.getByText('已结束', {
      exact: true
    }).waitFor();
    await enterprise.screenshot({
      path: path.join(input.directory, 'enterprise-completed.png'),
      fullPage: true
    });
    results.push('completed transport shared by all three roles');
    step = 'old device workspace';
    await enterprise.getByRole('link', {
      name: '设备监测',
      exact: true
    }).click();
    await enterprise.waitForURL('**/boxes');
    await enterprise.locator('a[href="/boxes/BOX-0001"]').waitFor();
    step = 'guest refresh clears access';
    await viewer.reload();
    await viewer.getByRole('button', {
      name: '验证并查看',
      exact: true
    }).waitFor();
    assert((await viewer.getByLabel('查看验证码', {
      exact: true
    }).inputValue()) === '', 'guest credential persisted');
    assert(await viewer.evaluate(() => localStorage.length === 0 && sessionStorage.length === 0),
      'browser credentials persisted');
    results.push('legacy device page retained; guest refresh clears token');
    await save('browser-summary', {
      passed: true,
      results,
      viewports: ['1440x900', '390x844'],
      real_hardware: false
    });
    console.log('PASS TASK-013 three-role browser flow');
  } catch (error) {
    await save('browser-failure', {
      step,
      error_kind: error.name || 'BrowserCheckFailed'
    });
    throw Error('Browser failed at ' + step);
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
