/* TASK-014 controlled-response browser verification. No database, device or real credential is used. */
const {
  chromium
} = require('../node_modules/@playwright/test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..'),
  out = path.join(root, 'artifacts/task014-ui-restore'),
  base = process.env.SMARTBOX_UI_URL || 'http://127.0.0.1:15174';
const sizes = [
  ['mobile', 390, 844],
  ['tablet', 768, 1024],
  ['desktop', 1440, 900]
];
const encode = v => JSON.stringify(v, (_, x) => typeof x === 'bigint' ? '__bigint' + x : x).replace(/"__bigint(\d+)"/g,
  '$1');
async function setup(browser, role, viewport = {
  width: 390,
  height: 844
}) {
  const context = await browser.newContext({
    viewport
  });
  const page = await context.newPage();
  const now = Date.now();
  const state = {
    role,
    quality: 'AVAILABLE',
    invalidCode: false,
    expired: false,
    deny: false,
    offline: false,
    risk: true,
    requests: [],
    createCount: 0,
    startCount: 0,
    delayed: false,
    delayMs: 0,
    assigned: true,
    tasks: [{
      task_id: 1,
      transport_no: 'TR-UI-ACTIVE',
      box_id: 'BOX-UI-01',
      status: 'ACTIVE',
      item_name: '受控测试运输',
      item_description: '显式受控响应，不是真实运行数据',
      started_at: now - 360000,
      ended_at: null
    }, {
      task_id: 2,
      transport_no: 'TR-UI-READY',
      box_id: 'BOX-UI-02',
      status: 'READY',
      item_name: '已保存的物品',
      item_description: '已有物品说明',
      started_at: null,
      ended_at: null
    }, {
      task_id: 3,
      transport_no: 'TR-UI-HISTORY',
      box_id: 'BOX-UI-01',
      status: 'COMPLETED',
      item_name: '前次运输',
      item_description: '历史独立',
      started_at: now - 7200000,
      ended_at: now - 3600000
    }],
    guestTask: 1
  };
  const assets = () => [{
    box_id: 'BOX-UI-01',
    display_name: '企业周转箱',
    source: 'ASSIGNED',
    rider_user_id: state.assigned ? 9007199254740993n : null,
    rider_username: state.assigned ? '受控骑手' : null
  }, {
    box_id: 'BOX-UI-02',
    display_name: '个人随行箱',
    source: 'OWNED',
    rider_user_id: null,
    rider_username: null
  }];
  const monitoring = t => {
    const value = t.status === 'COMPLETED' ? 5.1 : t.box_id === 'BOX-UI-02' ? 6.2 : 4.8;
    const time = t.ended_at || now;
    const dataState = t.status === 'READY' ? 'READY' : state.quality;
    return {
      task: role === 'guest' ? Object.fromEntries(Object.entries(t).filter(([k]) => k !== 'box_id')) : t,
      environment: {
        assessment: 'UNKNOWN',
        latest_temperature: ['INVALID', 'INSUFFICIENT', 'READY'].includes(dataState) ? null : value,
        latest_humidity: ['INVALID', 'INSUFFICIENT', 'READY'].includes(dataState) ? null : 61,
        observed_at: dataState === 'READY' || dataState === 'INSUFFICIENT' ? null : time,
        time_quality: dataState === 'INSUFFICIENT' ? 'UNKNOWN' : 'SNTP',
        data_state: dataState
      },
      samples: t.status === 'READY' ? [] : [{
        id: 3,
        observed_at: time - 1000,
        temperature: value,
        humidity: 61,
        valid: true
      }, {
        id: 2,
        observed_at: time - 2000,
        temperature: null,
        humidity: null,
        valid: false
      }, {
        id: 1,
        observed_at: time - 3000,
        temperature: 0,
        humidity: 60,
        valid: true
      }],
      events: t.status === 'READY' ? [] : [{
        id: 1,
        observed_at: time - 2000,
        type: 'DEVICE_FAULT',
        level: 'WARN',
        description: '受控设备事件'
      }]
    };
  };
  await page.route('**/api/v1/**', async route => {
    const req = route.request(),
      url = new URL(req.url()),
      p = url.pathname.replace('/api/v1', '');
    const body = req.postData() ? JSON.parse(req.postData()) : {};
    state.requests.push({
      path: p,
      method: req.method(),
      body: req.postData(),
      headers: req.headers()
    });
    const reply = (data, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      body: encode({
        data,
        request_id: 'controlled-task014'
      })
    });
    const reject = (code, status = 409) => route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify({
        error: {
          code
        },
        request_id: 'controlled-task014'
      })
    });
    if (state.deny && p.startsWith('/rider/')) return reject('FORBIDDEN', 403);
    if (p === '/auth/login') return reply({
      access_token: 'controlled-memory-only',
      expires_at: now + 3600000
    });
    if (p === '/me') return reply({
      username: '受控测试用户',
      role,
      box_ids: []
    });
    if (p === '/enterprise/boxes') return reply({
      enterprise_name: '受控测试企业',
      boxes: assets()
    });
    if (p === '/rider/boxes') return reply(assets());
    if (p === '/enterprise/riders') return reply([{
      user_id: 9007199254740993n,
      username: '受控骑手'
    }]);
    if (/\/enterprise\/boxes\/[^/]+\/assign$/.test(p)) {
      state.assigned = true;
      return reply({});
    }
    if (/\/enterprise\/boxes\/[^/]+\/unassign$/.test(p)) {
      if (state.tasks.some(t => t.box_id === 'BOX-UI-01' && t.status === 'ACTIVE')) return reject(
        'ACTIVE_TRANSPORT_EXISTS');
      state.assigned = false;
      return reply({});
    }
    if (p === '/rider/transports' && req.method() === 'POST') {
      state.createCount++;
      const t = {
        task_id: state.tasks.length + 1,
        transport_no: 'TR-UI-NEW-' + state.tasks.length,
        box_id: body.box_id,
        status: 'READY',
        item_name: null,
        item_description: null,
        started_at: null,
        ended_at: null
      };
      state.tasks.push(t);
      return reply(t);
    }
    if (p === '/rider/transports' || p === '/enterprise/transports') return reply(state.tasks);
    if (p === '/public/transport-access/verify') {
      if (body.code !== 'controlled-code' || state.invalidCode) return reject('INVALID_TRANSPORT_ACCESS', 401);
      state.guestTask = body.transport_no === 'TR-UI-HISTORY' ? 3 : 1;
      return reply({
        token: 'controlled-guest-memory',
        expires_at: Date.now() + 3600000
      });
    }
    if (p === '/public/transport-view') {
      if (state.expired) return reject('INVALID_TRANSPORT_ACCESS', 401);
      const value = monitoring(state.tasks.find(t => t.task_id === state.guestTask));
      if (state.delayMs) await new Promise(r => setTimeout(r, state.delayMs));
      return reply(value).catch(() => {});
    }
    const match = p.match(/^\/(?:enterprise|rider)\/transports\/(\d+)(?:\/(.*))?$/);
    if (match) {
      const t = state.tasks.find(t => String(t.task_id) === match[1]);
      if (!t) return reject('NOT_FOUND', 404);
      switch (match[2]) {
        case undefined:
          return reply(t);
        case 'monitoring': {
          const value = monitoring(t);
          if (state.delayed && t.task_id === 1) await new Promise(r => setTimeout(r, 1000));
          return reply(value).catch(() => {});
        }
        case 'item':
          assert.equal(t.status, 'READY');
          t.item_name = body.name;
          t.item_description = body.description;
          return reply(t);
        case 'start':
          state.startCount++;
          if (state.offline) return reject('BOX_OFFLINE');
          if (state.tasks.some(x => x.task_id !== t.task_id && x.box_id === t.box_id && x.status === 'ACTIVE'))
            return reject('BOX_IN_USE');
          if (state.risk && !body.confirm_risk) return reject('RISK_CONFIRMATION_REQUIRED');
          t.status = 'ACTIVE';
          t.started_at = Date.now();
          return reply(t);
        case 'finish':
          if (t.status !== 'COMPLETED') {
            t.status = 'COMPLETED';
            t.ended_at = Date.now();
          }
          return reply(t);
        case 'access-code':
          return reply({
            code: 'PUBLIC-FAKE-CODE'
          });
      }
    }
    return reject('UNEXPECTED_TEST_REQUEST', 404);
  });
  return {
    page,
    context,
    state,
    monitoring
  };
}
async function login(page, role) {
  await page.goto(base + '/login');
  await page.locator('#username').fill('controlled');
  await page.locator('#password').fill('not-real');
  await page.locator('.login-submit').click();
  await page.waitForURL(role === 'COURIER' ? '**/rider' : '**/enterprise');
}
async function guest(page, no = 'TR-UI-ACTIVE') {
  await page.goto(base + '/track');
  await page.getByLabel('运输编号', {
    exact: true
  }).fill(no);
  await page.getByLabel('查看验证码', {
    exact: true
  }).fill('controlled-code');
  await page.getByRole('button', {
    name: '验证并查看',
    exact: true
  }).click();
  await page.locator('.transport-header').waitFor();
}
async function run() {
  fs.mkdirSync(path.join(out, 'after'), {
    recursive: true
  });
  const browser = await chromium.launch();
  const report = {
    source: 'controlled API responses; not real MySQL/MQTT integration',
    views: 0,
    screenshots: [],
    checks: [],
    pageErrors: []
  };
  const capture = async (p, name) => {
    await p.mouse.move(0, 0);
    await p.evaluate(() => scrollTo(0, 0));
    await p.screenshot({
      path: path.join(out, 'after', name + '.png'),
      fullPage: true
    });
    report.screenshots.push(name + '.png');
  };
  const fits = async (p) => {
    assert.ok(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      'horizontal overflow');
    report.views++;
  };
  try {
    for (const [device, width, height] of sizes) {
      for (const role of ['COURIER', 'LOGISTICS_OPERATOR', 'guest']) {
        const {
          page: p,
          context,
          state
        } = await setup(browser, role, {
          width,
          height
        });
        p.on('pageerror', e => report.pageErrors.push(e.message));
        if (role === 'guest') await guest(p);
        else await login(p, role);
        await p.getByText('受控测试运输', {
          exact: true
        }).first().waitFor();
        await fits(p);
        await capture(p, `${role==='guest'?'customer':role==='COURIER'?'rider':'enterprise'}-${device}`);
        if (role === 'COURIER') {
          assert.ok(await p.locator('.environment-conclusion').first().isVisible());
          assert.equal(await p.locator('.sample-history').count(), 0);
          if (width === 390) {
            const b = await p.getByRole('link', {
              name: '查看当前运输',
              exact: true
            }).boundingBox();
            assert.ok(b.y + b.height < height - 76, 'primary action covered');
          }
          for (const mode of ['boxes', 'history', 'account']) {
            await p.getByRole('navigation', {
              name: width === 390 ? '移动导航' : '主要导航'
            }).getByRole('link', {
              name: mode === 'boxes' ? '我的箱体' : mode === 'history' ? '运输记录' : '我的',
              exact: true
            }).click();
            await fits(p);
          }
          await p.getByRole('link', {
            name: '工作台',
            exact: true
          }).first().click();
          await p.getByRole('link', {
            name: '开始新运输',
            exact: true
          }).click();
          await p.getByRole('heading', {
            name: '选择运输箱体'
          }).waitFor();
          await capture(p, `rider-prepare-${device}`);
          await fits(p);
        }
        if (role === 'guest') {
          assert.equal(await p.locator('.mobile-nav,.desktop-nav').count(), 0);
          assert.ok(!(await p.locator('.main-content').innerText()).includes('BOX-UI'));
          await p.getByRole('button', {
            name: '查看环境记录',
            exact: true
          }).click();
          await fits(p);
          assert.equal(await p.locator('.real-chart circle').count(), 2);
          await capture(p, `customer-history-${device}`);
          await p.getByRole('button', {
            name: '返回运输概览',
            exact: true
          }).click();
          await p.getByRole('button', {
            name: '设备事件',
            exact: true
          }).click();
          await fits(p);
          await capture(p, `customer-events-${device}`);
        }
        if (role === 'LOGISTICS_OPERATOR') {
          assert.ok(await p.locator('.enterprise-sidebar').isVisible());
          assert.equal(await p.locator('.enterprise-map button').count(), 0);
          assert.ok((await p.locator('.enterprise-map').innerText()).includes('无真实 GPS'));
          await p.getByRole('navigation', {
            name: '企业页面分区'
          }).getByRole('link', {
            name: '资产',
            exact: true
          }).click();
          await fits(p);
          await capture(p, `enterprise-assets-${device}`);
        }
        await context.close();
      }
    }
    console.log('Three-size structure and screenshots passed');
    for (const [device, width, height] of [sizes[0], sizes[2]]) {
      const {
        page: p,
        context,
        state
      } = await setup(browser, 'COURIER', {
        width,
        height
      });
      p.on('pageerror', e => report.pageErrors.push(e.message));
      await login(p, 'COURIER');
      await p.getByRole('link', {
        name: '开始新运输',
        exact: true
      }).click();
      await p.getByRole('button', {
        name: /企业周转箱.*正在运输/
      }).waitFor();
      assert.ok(await p.getByRole('button', {
        name: /企业周转箱.*正在运输/
      }).isDisabled());
      await p.getByRole('button', {
        name: /个人随行箱/
      }).click();
      await p.getByRole('button', {
        name: '下一步：绑定物品',
        exact: true
      }).click();
      await p.waitForURL(/\/rider\/transports\/4/);
      assert.equal(state.createCount, 1);
      await p.getByLabel('物品名称', {
        exact: true
      }).fill('保存后的物品');
      await p.getByLabel('物品说明（可选）', {
        exact: true
      }).fill('保留草稿与编辑输入');
      await p.getByRole('button', {
        name: '保存物品并核对',
        exact: true
      }).click();
      await p.getByRole('heading', {
        name: '核对并开始运输'
      }).waitFor();
      await capture(p, `rider-review-${device}`);
      await p.getByRole('button', {
        name: '修改物品',
        exact: true
      }).click();
      assert.equal(await p.getByLabel('物品名称', {
        exact: true
      }).inputValue(), '保存后的物品');
      await p.getByRole('button', {
        name: '上一步',
        exact: true
      }).click();
      await p.getByRole('button', {
        name: '更换箱体并另建',
        exact: true
      }).click();
      await p.getByRole('dialog').getByRole('button', {
        name: '返回核对',
        exact: true
      }).click();
      assert.equal(state.createCount, 1);
      await p.reload();
      await p.waitForURL(/\/login/);
      await p.locator('#username').fill('controlled');
      await p.locator('#password').fill('not-real');
      await p.locator('.login-submit').click();
      await p.getByRole('heading', {
        name: '核对并开始运输'
      }).waitFor();
      await p.getByRole('button', {
        name: '修改物品',
        exact: true
      }).click();
      assert.equal(await p.getByLabel('物品名称', {
        exact: true
      }).inputValue(), '保存后的物品');
      await p.getByLabel('物品名称', {
        exact: true
      }).fill('编辑中的名称');
      await p.clock.install();
      await p.clock.fastForward(11000);
      assert.equal(await p.getByLabel('物品名称', {
        exact: true
      }).inputValue(), '编辑中的名称');
      await p.getByRole('button', {
        name: '保存物品并核对',
        exact: true
      }).click();
      await p.getByRole('heading', {
        name: '核对并开始运输'
      }).waitFor();
      state.offline = true;
      await p.getByRole('button', {
        name: '确认并开始运输',
        exact: true
      }).click();
      await p.getByRole('dialog').getByRole('button', {
        name: '开始运输',
        exact: true
      }).click();
      await p.getByRole('alert').filter({
        hasText: '离线'
      }).waitFor();
      assert.equal(state.tasks[3].status, 'READY');
      state.offline = false;
      await p.getByRole('button', {
        name: '确认并开始运输',
        exact: true
      }).click();
      await p.getByRole('dialog').getByRole('button', {
        name: '开始运输',
        exact: true
      }).click();
      await p.getByRole('dialog').getByRole('button', {
        name: '我已知晓风险，继续开始',
        exact: true
      }).click();
      await p.locator('.transport-header').waitFor();
      assert.equal(state.tasks[3].status, 'ACTIVE');
      await p.getByRole('button', {
        name: '结束运输',
        exact: true
      }).click();
      assert.equal(state.tasks[3].status, 'ACTIVE');
      await p.getByRole('dialog').getByRole('button', {
        name: '结束运输',
        exact: true
      }).click();
      await p.getByText('本次运输已结束，以下仅为本次历史记录。').waitFor();
      assert.equal(await p.getByRole('button', {
        name: '结束运输',
        exact: true
      }).count(), 0);
      await capture(p, `rider-completed-${device}`);
      await p.getByRole('button', {
        name: '生成查看验证码',
        exact: true
      }).click();
      await p.locator('[aria-label="运输验证码"]').waitFor();
      await p.getByRole('button', {
        name: '隐藏验证码',
        exact: true
      }).click();
      await p.getByRole('button', {
        name: '查看环境记录',
        exact: true
      }).click();
      assert.equal(await p.locator('[aria-label="运输验证码"]').count(), 0);
      report.checks.push(
        `${device}: draft identity, occupied box, reload, form refill/edit protection, offline/risk, finish confirmation, share visibility`
        );
      await context.close();
      const g = await setup(browser, 'guest', {
        width,
        height
      });
      await g.page.goto(base + '/track');
      await g.page.getByLabel('运输编号', {
        exact: true
      }).fill('TR-UI-ACTIVE');
      await g.page.getByLabel('查看验证码', {
        exact: true
      }).fill('wrong');
      await g.page.getByRole('button', {
        name: '验证并查看',
        exact: true
      }).click();
      await g.page.getByRole('alert').waitFor();
      assert.equal(await g.page.locator('.reading-number').count(), 0);
      await capture(g.page, `customer-invalid-code-${device}`);
      await guest(g.page, 'TR-UI-HISTORY');
      assert.ok((await g.page.locator('.main-content').innerText()).includes('5.1'));
      assert.ok(!(await g.page.locator('.main-content').innerText()).includes('4.8'));
      g.state.expired = true;
      await g.page.getByRole('button', {
        name: '刷新',
        exact: true
      }).click();
      await g.page.getByRole('button', {
        name: '验证并查看',
        exact: true
      }).waitFor();
      assert.equal(await g.page.locator('.reading-number').count(), 0);
      await capture(g.page, `customer-expired-${device}`);
      assert.ok(g.state.requests.filter(x => x.path === '/public/transport-view').every(x => !x.headers
        .authorization && x.headers['x-transport-view-token']));
      assert.equal(await g.page.evaluate(() => localStorage.length + sessionStorage.length), 0);
      report.checks.push(
        `${device}: wrong code, historical scope, expiry, dedicated header, no persistent credential`);
      await g.context.close();
    }
    for (const quality of ['INSUFFICIENT', 'INVALID', 'AVAILABLE']) {
      const g = await setup(browser, 'guest');
      g.state.quality = quality;
      await guest(g.page);
      assert.ok((await g.page.locator('.main-content').innerText()).includes(quality === 'INVALID' ? '采样无效' :
        quality === 'INSUFFICIENT' ? '暂无可确认' : '环境结论暂无法确认'));
      await capture(g.page, 'customer-' + quality.toLowerCase());
      await g.context.close();
    }
    const c = await setup(browser, 'LOGISTICS_OPERATOR', {
      width: 1440,
      height: 900
    });
    await login(c.page, 'LOGISTICS_OPERATOR');
    await c.page.getByRole('button', {
      name: '收回分配',
      exact: true
    }).click();
    await c.page.getByRole('alert').filter({
      hasText: '运输中不能收回'
    }).waitFor();
    c.state.tasks[0].status = 'COMPLETED';
    await c.page.getByRole('button', {
      name: '收回分配',
      exact: true
    }).click();
    await c.page.getByText('尚未分配', {
      exact: true
    }).first().waitFor();
    await c.page.getByLabel('选择骑手 BOX-UI-01', {
      exact: true
    }).selectOption('9007199254740993');
    await c.page.getByRole('button', {
      name: '分配箱体',
      exact: true
    }).first().click();
    await c.page.getByText('使用骑手：受控骑手', {
      exact: true
    }).waitFor();
    assert.ok(c.state.requests.some(r => r.path.endsWith('/assign') && r.body.includes('9007199254740993') && !r.body
      .includes('9007199254740992')));
    report.checks.push('enterprise assignment BigInt preserved; active reclaim rejection and completed reclaim');
    await c.context.close();
    const edge=await setup(browser,'COURIER');
    edge.state.tasks=[];
    await login(edge.page,'COURIER');
    await edge.page.getByRole('heading',{name:'当前没有运输',exact:true}).waitFor();
    await capture(edge.page,'rider-empty-task-mobile');
    await edge.page.getByRole('link',{name:'开始新运输',exact:true}).click();
    await edge.page.getByRole('button',{name:/个人随行箱/}).click();
    let creates=0;
    await edge.page.route('**/api/v1/rider/transports',async r=>{
      if(r.request().method()!=='POST')return r.fallback();
      creates++;return r.fulfill({status:503,json:{error:{code:'DEPENDENCY_UNAVAILABLE'},request_id:'controlled'}});
    });
    await edge.page.getByRole('button',{name:'下一步：绑定物品',exact:true}).click();
    await edge.page.getByRole('alert').filter({hasText:'创建结果尚未确认'}).waitFor();
    assert.ok(await edge.page.getByRole('button',{name:'下一步：绑定物品',exact:true}).isDisabled());
    assert.equal(creates,1);
    await edge.context.close();
    const multi=await setup(browser,'COURIER');
    multi.state.tasks[1].status='ACTIVE';multi.state.tasks[1].started_at=Date.now()-60000;
    await login(multi.page,'COURIER');
    await multi.page.locator('.current-transport').nth(1).locator('.environment').waitFor();
    assert.equal(await multi.page.locator('.current-transport').count(),2);
    assert.ok((await multi.page.locator('.current-transport').nth(0).innerText()).includes('4.8'));
    assert.ok((await multi.page.locator('.current-transport').nth(1).innerText()).includes('6.2'));
    multi.state.deny=true;await multi.page.getByRole('button',{name:'刷新',exact:true}).click();
    await multi.page.getByRole('alert').waitFor();
    assert.equal(await multi.page.locator('.current-transport').count(),0);
    await multi.context.close();
    const late=await setup(browser,'guest');await guest(late.page);
    late.state.delayMs=700;await late.page.getByRole('button',{name:'刷新',exact:true}).click();
    await late.page.getByRole('button',{name:'退出查看',exact:true}).click();
    await new Promise(r=>setTimeout(r,900));
    assert.equal(await late.page.locator('.reading-number').count(),0);
    await late.context.close();
    report.checks.push('empty workbench; uncertain creation never auto-retries; two task readings isolated; 403 clears scope; late guest response cannot restore exited data');
    assert.deepEqual(report.pageErrors, []);
    fs.writeFileSync(path.join(out, 'checks-controlled.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify({
      ...report,
      screenshots: report.screenshots.length
    }, null, 2));
  } finally {
    await browser.close();
  }
}
if (require.main === module) run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
module.exports = {
  setup,
  login,
  guest,
  base
};
