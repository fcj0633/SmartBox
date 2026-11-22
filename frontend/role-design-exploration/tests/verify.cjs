const {
  chromium
} = require('../../node_modules/@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const output = path.resolve(__dirname, '../../../artifacts/role-ux-optimization');
const shots = path.join(output, 'after');
const base = process.env.SMARTBOX_PREVIEW_URL || 'http://127.0.0.1:5187';
async function run() {
  fs.mkdirSync(shots, {
    recursive: true
  });
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const errors = [],
    requests = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', r => {
    const u = new URL(r.url());
    if (!['localhost', '127.0.0.1'].includes(u.hostname) || u.pathname.startsWith('/api/')) requests.push(r
  .url());
  });
  const report = {
    responsiveViews: 0,
    scenarios: 0,
    interactions: [],
    screenshots: [],
    errors,
    requests
  };
  async function visit(role = 'rider', view = 'home', scenario = 'normal', task = 'DEMO-1123-018', review = false) {
    await page.goto(
      `${base}/?role=${role}&page=${view}&scenario=${scenario}&task=${task}&presentation=${review?'0':'1'}`);
    await page.locator('.main-content h1').waitFor();
  }
  async function verify(task = 'DEMO-1123-018') {
    await page.getByLabel('运输编号', {
      exact: true
    }).fill(task);
    await page.getByLabel('验证码', {
      exact: true
    }).fill('DEMO-4821');
    await page.getByRole('button', {
      name: '查看运输',
      exact: true
    }).click();
    await page.locator('.transport-header').waitFor();
  }
  async function shot(name) {
    await page.mouse.move(0, 0);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: path.join(shots, name + '.png'),
      fullPage: true
    });
    report.screenshots.push(name + '.png');
    if (/home-(mobile|desktop|tablet)$/.test(name)) {
      await page.screenshot({
        path: path.join(shots, name + '-viewport.png')
      });
      report.screenshots.push(name + '-viewport.png');
    }
  }
  async function dimensions(label) {
    const d = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      width: innerWidth
    }));
    assert.ok(d.scroll <= d.width + 1, `${label} overflow ${JSON.stringify(d)}`);
    assert.ok((await page.locator('.main-content').innerText()).length > 15);
    report.responsiveViews++;
  }
  try {
    for (const [device, width, height] of [
        ['mobile', 390, 844],
        ['tablet', 768, 1024],
        ['desktop', 1440, 900]
      ]) {
      await page.setViewportSize({
        width,
        height
      });
      for (const role of ['rider', 'customer']) {
        await visit(role);
        if (role === 'customer') await verify();
        await shot(`${role}-home-${device}`);
        await dimensions(`${role} home ${device}`);
        assert.equal(await page.locator('.trend-plot').count(), 0, 'home has no full chart');
        const env = await page.locator('.environment-conclusion').first().boundingBox();
        assert.ok(env && env.y < height - 60, `${role} environment conclusion above fold`);
        if (role === 'customer') assert.equal(await page.locator('.mobile-nav,.desktop-nav').count(), 0);
        else if (device === 'mobile') {
          const button = await page.getByRole('button', {
            name: '查看当前运输',
            exact: true
          }).boundingBox();
          assert.ok(button.y + button.height < height - 76, 'main action not covered by navigation');
        }
        for (const scene of ['alarm', 'offline', 'stale', 'invalid', 'unknown', 'expired', ...(role === 'rider' ? [
            'idle', 'empty', 'revoked', 'multiple'
          ] : [])]) {
          await visit(role, 'home', scene);
          if (role === 'customer' && scene !== 'expired') await verify();
          await dimensions(`${role} ${scene} ${device}`);
          report.scenarios++;
          if (scene === 'invalid') assert.ok((await page.locator('.environment').first().innerText()).includes('—'));
          if (scene === 'expired') assert.equal(await page.locator('.reading-number').count(), 0);
          if (scene === 'offline' || scene === 'stale') assert.ok((await page.locator('.main-content').innerText())
            .includes('最后有效读数'));
          if (scene === 'idle') assert.ok((await page.locator('.main-content').innerText()).includes('当前没有运输'));
          if (scene === 'multiple') assert.equal(await page.getByRole('button', {
            name: '查看当前运输',
            exact: true
          }).count(), 2);
          if (device !== 'tablet') await shot(`${role}-${scene}-${device}`);
        }
        await visit(role);
        if (role === 'customer') await verify('DEMO-1123-006');
        else await page.getByRole('button', {
          name: '查看当前运输',
          exact: true
        }).click();
        if (role === 'customer') {
          const c = await page.locator('.main-content').innerText();
          assert.ok(c.includes('5.1') && !c.includes('4.8'));
          await shot(`customer-completed-${device}`);
        }
        for (const view of ['environment', 'alerts']) {
          if (role === 'customer') await page.getByRole('button', {
            name: view === 'environment' ? '查看环境记录' : '异常记录',
            exact: true
          }).click();
          else {
            await visit(role, 'task');
            await page.getByRole('button', {
              name: view === 'environment' ? '查看环境记录' : '异常记录',
              exact: true
            }).click();
          }
          await dimensions(`${role} ${view} ${device}`);
          if (view === 'environment') {
            assert.equal(await page.locator('.trend-plot').count(), 1);
          }
          await shot(`${role}-${view}-${device}`);
          if (role === 'customer') await page.getByRole('button', {
            name: '返回运输概览',
            exact: true
          }).click();
        }
      }
      for (const view of ['boxes', 'box', 'history', 'account', 'prepare', 'entry']) {
        await visit('rider', view);
        await dimensions(`${view} ${device}`);
        if (['prepare', 'entry'].includes(view)) await shot(`rider-${view}-${device}`);
      }
      await visit('customer', 'entry');
      await dimensions(`customer entry ${device}`);
      await shot(`customer-entry-${device}`);
    }
    console.log('Responsive and state captures passed');
    for (const [device, width, height] of [
        ['mobile', 390, 844],
        ['desktop', 1440, 900]
      ]) {
      await page.setViewportSize({
        width,
        height
      });
      await visit('rider', 'prepare');
      assert.ok(await page.getByRole('button', {
        name: /随行箱.*正在运输/
      }).isDisabled());
      await page.getByRole('button', {
        name: /周转箱.*可使用/
      }).click();
      await page.getByRole('button', {
        name: '下一步：绑定物品',
        exact: true
      }).click();
      await page.getByRole('button', {
        name: '绑定并核对',
        exact: true
      }).click();
      assert.ok(await page.getByRole('alert').isVisible());
      await page.getByLabel('物品名称', {
        exact: true
      }).fill('企业箱鲜蔬');
      await shot(`rider-binding-${device}`);
      await page.getByRole('button', {
        name: '绑定并核对',
        exact: true
      }).click();
      assert.ok((await page.locator('.definition-list').innerText()).includes('SBX-24021'));
      await shot(`rider-bound-${device}`);
      await page.getByRole('button', {
        name: '确认并开始运输',
        exact: true
      }).click();
      await page.getByRole('dialog').getByRole('button', {
        name: '开始运输',
        exact: true
      }).focus();
      await page.keyboard.press('Tab');
      assert.equal(await page.evaluate(() => document.activeElement.getAttribute('aria-label')), '关闭弹窗');
      await page.getByRole('dialog').getByRole('button', {name:'开始运输',exact:true}).click();
      await page.locator('.transport-header').waitFor();
      assert.ok((await page.locator('.transport-header').innerText()).includes('企业箱鲜蔬'));
      await shot(`rider-started-${device}`);
      await page.getByRole('button', {
        name: '结束运输',
        exact: true
      }).click();
      await page.getByRole('dialog').getByRole('button', {
        name: '结束运输',
        exact: true
      }).click();
      assert.ok((await page.locator('.transport-header').innerText()).includes('已结束'));
      assert.equal(await page.getByRole('button', {
        name: '结束运输',
        exact: true
      }).count(), 0);
      await shot(`rider-ended-${device}`);
      await page.getByRole('button', {
        name: '返回运输记录',
        exact: true
      }).click();
      await page.getByRole('button', {
        name: /鲜果礼盒.*运输中/
      }).click();
      assert.ok((await page.locator('.transport-header').innerText()).includes('SBX-24018'));
      await page.getByRole('button', {
        name: '结束运输',
        exact: true
      }).click();
      await page.getByRole('dialog').getByRole('button', {
        name: '结束运输',
        exact: true
      }).click();
      const navigation = page.locator(device === 'mobile' ? '.mobile-nav' : '.desktop-nav');
      await navigation.getByRole('button', {
        name: '我的箱体',
        exact: true
      }).click();
      await page.getByRole('button', {
        name: /随行箱.*可使用/
      }).click();
      await page.getByRole('button', {
        name: '开始新运输',
        exact: true
      }).click();
      await page.getByRole('button', {
        name: '下一步：绑定物品',
        exact: true
      }).click();
      await page.getByLabel('物品名称', {
        exact: true
      }).fill('个人箱新任务');
      await page.getByRole('button', {
        name: '绑定并核对',
        exact: true
      }).click();
      const summary = await page.locator('.definition-list').innerText();
      assert.ok(summary.includes('SBX-24018') && summary.includes('DEMO-1123-N'));
      await page.getByRole('button', {
        name: '确认并开始运输',
        exact: true
      }).click();
      await page.getByRole('dialog').getByRole('button', {
        name: '开始运输',
        exact: true
      }).click();
      await navigation.getByRole('button', {
        name: '运输记录',
        exact: true
      }).click();
      await page.getByRole('button', {
        name: /蔬果组合/
      }).click();
      assert.ok((await page.locator('.main-content').innerText()).includes('5.1'));
      assert.ok((await page.locator('.transport-header').innerText()).includes('DEMO-1123-006'));
      report.interactions.push(
        `${device}: occupied box, required binding, enterprise task start/end, no duplicate end, personal box reuse generates independent task, old task 5.1 unchanged`
        );
      await visit('customer', 'home');
      assert.equal(await page.locator('.transport-header,.reading-number').count(), 0,
        'query cannot bypass verification');
      await page.getByLabel('验证码', {
        exact: true
      }).fill('WRONG');
      await page.getByRole('button', {
        name: '查看运输',
        exact: true
      }).click();
      assert.ok(await page.getByRole('alert').isVisible());
      await shot(`customer-verification-error-${device}`);
      await verify();
      await page.getByRole('button', {
        name: '退出查看',
        exact: true
      }).click();
      assert.equal(await page.locator('.reading-number').count(), 0);
      await visit('customer', 'home', 'alarm');
      await verify();
      await page.getByRole('button', {
        name: '异常记录',
        exact: true
      }).click();
      await page.getByRole('button', {
        name: /温度超出演示范围/
      }).click();
      assert.ok(await page.getByRole('dialog').isVisible());
      assert.equal(await page.getByRole('button', {
        name: /已处理|确认告警/
      }).count(), 0);
      await shot(`customer-alert-detail-${device}`);
      await page.keyboard.press('Escape');
      assert.equal(await page.getByRole('dialog').count(), 0);
      await visit('customer', 'home', 'normal', 'DEMO-1123-006', true);
      await verify('DEMO-1123-006');
      await page.getByLabel('演示场景', {
        exact: true
      }).selectOption('alarm');
      await verify('DEMO-1123-006');
      assert.ok((await page.locator('.main-content').innerText()).includes('5.1'));
      assert.ok(!(await page.locator('.main-content').innerText()).includes('8.6'));
      await page.getByLabel('演示场景', {
        exact: true
      }).selectOption('expired');
      assert.equal(await page.locator('.reading-number').count(), 0);
      await page.getByRole('button', {
        name: '重新验证',
        exact: true
      }).click();
      assert.equal(await page.locator('.reading-number').count(), 0);
      report.interactions.push(
        `${device}: guest query guard, invalid code, verified entry, exit clears display, alert read-only and Escape, old task unaffected by current alarm, expiry removes content`
        );
      await visit('rider', 'home', 'revoked');
      await page.getByRole('button', {
        name: /周转箱/
      }).click();
      assert.ok(await page.getByRole('button', {
        name: '开始新运输',
        exact: true
      }).isDisabled());
      await visit('rider', 'prepare', 'offline');
      assert.ok(await page.getByRole('button', {
        name: '下一步：绑定物品',
        exact: true
      }).isDisabled());
      report.interactions.push(`${device}: assignment revoked and offline start blocked in demo`);
      await visit('rider', 'task', 'alarm');
      await page.getByRole('button', {name:'结束运输',exact:true}).click();
      await page.getByRole('dialog').getByRole('button', {name:'结束运输',exact:true}).click();
      assert.ok((await page.locator('.monitor-summary').innerText()).includes('14:26'));
      assert.ok((await page.locator('.monitor-summary').innerText()).includes('8.6'));
      await page.getByRole('button', {name:'异常记录',exact:true}).click();
      assert.ok((await page.locator('.alert-record').innerText()).includes('14:26'));
      await visit('rider', 'task', 'offline');
      await page.getByRole('button', {name:'结束运输',exact:true}).click();
      await page.getByRole('dialog').getByRole('button', {name:'结束运输',exact:true}).click();
      assert.ok((await page.locator('.environment-conclusion').innerText()).includes('14:20'));
      report.interactions.push(`${device}: completion freezes anomaly time and last valid offline sample time`);
    }
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, []);
    // Comparison is a browser layout of original rendered captures, not an invented mockup.
    for (const role of ['rider', 'customer']) {
      const board = await browser.newPage({
        viewport: {
          width: 880,
          height: 1300
        }
      });
      const before = fs.readFileSync(path.join(output, 'before', `${role}-${role==='rider'?'b':'a'}-mobile.png`))
        .toString('base64');
      const after = fs.readFileSync(path.join(shots, `${role}-home-mobile.png`)).toString('base64');
      await board.setContent(
        `<html><meta charset="utf-8"><style>body{font-family:'Microsoft YaHei',sans-serif;margin:0;padding:24px;background:#e9efeb;color:#183746}h1{font-size:24px}section{display:flex;align-items:flex-start;gap:24px}article{width:390px}img{width:390px;display:block}h2{font-size:18px}</style><h1>${role==='rider'?'骑手工作台':'普通用户运输概览'} · 改版对比</h1><section><article><h2>改版前</h2><img src="data:image/png;base64,${before}"></article><article><h2>改版后</h2><img src="data:image/png;base64,${after}"></article></section></html>`
        );
      await board.screenshot({
        path: path.join(output, `${role}-before-after.png`),
        fullPage: true
      });
      await board.close();
    }
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify({
      ...report,
      screenshots: report.screenshots.length
    }, null, 2));
  } finally {
    await browser.close();
  }
}
run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
