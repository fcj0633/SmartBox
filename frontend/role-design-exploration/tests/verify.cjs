/* Independent UI checks: no backend, account, database or hardware connection. */
const { chromium } = require('../../node_modules/@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../../..');
const output = path.join(root, 'artifacts/role-design-exploration');
const shots = path.join(output, 'screenshots');
const base = process.env.SMARTBOX_PREVIEW_URL || 'http://127.0.0.1:5187';
const riderPages = ['entry','home','box','prepare','task','history','environment','alerts','account','expired'];
const customerPages = ['entry','home','task','environment','alerts','expired'];

async function run() {
  fs.mkdirSync(shots, {recursive:true});
  const browser = await chromium.launch({headless:true});
  const page = await browser.newPage();
  const pageErrors = [];
  const forbiddenRequests = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/') || !['127.0.0.1','localhost'].includes(url.hostname)) forbiddenRequests.push(request.url());
  });
  const report = { layouts:4, responsiveViews:0, screenshots:[], interactions:[], pageErrors, forbiddenRequests };
  const url = (role,layout,view,extra='') => `${base}/?role=${role}&layout=${layout}&page=${view}&presentation=1${extra}`;
  async function visit(role,layout,view,extra='') {
    await page.goto(url(role,layout,view,extra), {waitUntil:'networkidle'});
    await page.locator('.main-content h1').waitFor();
  }
  async function shot(name,fullPage=false) {
    await page.screenshot({path:path.join(shots,name),fullPage});
    report.screenshots.push(name);
  }
  try {
    for (const role of ['rider','customer']) for (const layout of ['a','b']) {
      for (const [device,width,height] of [['mobile',390,844],['tablet',768,1024],['desktop',1440,900]]) {
        await page.setViewportSize({width,height});
        for (const view of role==='rider'?riderPages:customerPages) {
          await visit(role,layout,view);
          const dimensions = await page.evaluate(()=>({width:document.documentElement.scrollWidth,viewport:innerWidth}));
          assert.ok(dimensions.width<=dimensions.viewport+1,`${role}/${layout}/${view}/${device} overflow ${JSON.stringify(dimensions)}`);
          assert.ok((await page.locator('.main-content').innerText()).trim().length>10);
          report.responsiveViews++;
          if(view==='home' || (device==='mobile' && ['entry','prepare','task','environment','alerts','expired'].includes(view))) {
            await shot(`${role}-${layout}-${view}-${device}.png`);
          }
        }
      }
      console.log(`Responsive checks passed: ${role}/${layout}`);
    }
    await page.setViewportSize({width:390,height:844});
    for(const layout of ['a','b']) {
      await visit('rider',layout,'prepare');
      assert.ok(await page.getByRole('button',{name:/随行箱 · 01.*已有运输/}).isDisabled());
      await page.getByRole('button',{name:/下一步：绑定物品/}).click();
      await page.getByLabel('物品名称').fill('');
      await page.getByRole('button',{name:/绑定并核对/}).click();
      assert.ok((await page.getByRole('alert').innerText()).includes('物品名称'));
      await page.getByLabel('物品名称').fill('鲜果补给包');
      await page.getByRole('button',{name:/绑定并核对/}).click();
      await shot(`rider-${layout}-binding-confirm-mobile.png`);
      await page.getByRole('button',{name:/确认信息并开始运输/}).click();
      await page.getByRole('button',{name:'开始演示运输',exact:true}).click();
      assert.ok((await page.locator('.task-summary').innerText()).includes('运输中'));
      await page.getByRole('button',{name:/结束本次运输/}).click();
      await page.getByRole('button',{name:'结束演示运输',exact:true}).click();
      assert.ok((await page.locator('.task-summary').innerText()).includes('已结束'));
      assert.equal(await page.getByRole('button',{name:/结束本次运输/}).count(),0);
      await shot(`rider-${layout}-completed-mobile.png`);
      report.interactions.push(`rider ${layout}: occupied box/empty binding/start confirmation/finish/no repeated finish`);
    }
    for(const layout of ['a','b']) {
      await visit('customer',layout,'entry');
      await page.getByLabel('验证码',{exact:true}).fill('BAD-DEMO');
      await page.getByRole('button',{name:'查看运输',exact:true}).click();
      assert.ok(await page.getByRole('alert').isVisible());
      assert.equal(await page.locator('.reading-number').count(),0);
      await shot(`customer-${layout}-verification-error-mobile.png`);
      await page.getByLabel('运输编号',{exact:true}).fill('DEMO-1123-006');
      await page.getByLabel('验证码',{exact:true}).fill('DEMO-4821');
      await page.getByRole('button',{name:'查看运输',exact:true}).click();
      assert.ok((await page.locator('.main-content').innerText()).includes('已结束'));
      assert.ok((await page.locator('.main-content').innerText()).includes('5.1'));
      assert.ok(!(await page.locator('.main-content').innerText()).includes('4.8'));
      await page.locator('.mobile-nav').getByRole('button',{name:'异常',exact:true}).click();
      assert.ok((await page.locator('.main-content').innerText()).includes('DEMO-1123-006'));
      await page.locator('.mobile-nav').getByRole('button',{name:'环境',exact:true}).click();
      assert.ok((await page.locator('.main-content').innerText()).includes('5.1'));
      await shot(`customer-${layout}-completed-history-mobile.png`);
      assert.equal(await page.getByRole('button',{name:/结束本次运输|设备控制|准备新运输/}).count(),0);
      report.interactions.push(`customer ${layout}: verification failure/success/completed-task 5.1 versus current 4.8/read-only scope`);
    }
    for(const role of ['rider','customer']) for(const layout of ['a','b']) for(const scenario of ['alarm','offline','invalid','stale','unknown','expired']) {
      await visit(role,layout,'home',`&scenario=${scenario}`);
      const content = await page.locator('.main-content').innerText();
      if(scenario==='expired') assert.equal(await page.locator('.reading-number').count(),0);
      if(scenario==='invalid') { assert.ok(content.includes('采样无效')); assert.ok(content.includes('判断未知')); }
      if(scenario==='unknown') assert.ok(content.includes('判断未知'));
      if(scenario==='offline'||scenario==='stale') assert.ok(content.includes('数据过期'));
      if(scenario==='alarm') assert.ok(content.includes('8.6'));
      if(scenario==='alarm'||scenario==='expired') await shot(`${role}-${layout}-${scenario}-mobile.png`);
    }
    await visit('rider','a','home','&scenario=empty');
    assert.ok((await page.locator('.main-content').innerText()).includes('还没有可使用的箱体'));
    await shot('rider-a-empty-mobile.png');
    await visit('rider','a','home');
    // Reviewer controls can be shown in the normal (non-presentation) entry.
    await page.goto(`${base}/?role=rider&layout=a&page=home`);
    await page.getByLabel('演示场景',{exact:true}).selectOption('revoked');
    await page.getByRole('button',{name:/查看箱体详情/}).click();
    assert.ok(await page.getByRole('button',{name:'准备新运输',exact:true}).isDisabled());
    await shot('rider-a-revoked-mobile.png');
    await page.getByRole('button',{name:'B 运输工作台',exact:true}).click();
    assert.equal(new URL(page.url()).searchParams.get('layout'),'b');
    report.interactions.push('scenario quality/empty/revoked/URL layout switch');
    await visit('customer','a','alerts','&scenario=alarm');
    await page.getByRole('button',{name:/温度超出演示范围/}).click();
    assert.ok(await page.getByRole('dialog').isVisible());
    assert.equal(await page.getByRole('button',{name:'确认告警',exact:true}).count(),0);
    await shot('customer-a-alarm-detail-mobile.png');
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('dialog').count(),0);
    report.interactions.push('read-only alert detail/Escape');
    assert.deepEqual(pageErrors,[]);
    assert.deepEqual(forbiddenRequests,[]);

    // Render comparison boards in the browser from unmodified viewport screenshots.
    for(const role of ['rider','customer']) {
      const board = await browser.newPage({viewport:{width:1000,height:1120},deviceScaleFactor:1});
      const title=role==='rider'?'骑手 / 个人箱体拥有者':'普通运输用户';
      const names=role==='rider'?['A · 我的箱体','B · 运输工作台']:['A · 运输时间线','B · 环境监测页'];
      const images=['a','b'].map(layout=>fs.readFileSync(path.join(shots,`${role}-${layout}-home-mobile.png`)).toString('base64'));
      await board.setContent(`<html lang="zh-CN"><meta charset="UTF-8"><style>*{box-sizing:border-box}body{margin:0;background:#e9efeb;color:#183746;font-family:'Segoe UI','Microsoft YaHei',sans-serif;padding:35px 46px}h1{font-size:27px;margin:0 0 7px}p{font-size:13px;color:#7b8f82;margin:0}section{display:flex;justify-content:space-between;gap:42px;margin-top:27px}article{flex:1;min-width:0}h2{font-size:17px;font-weight:600;margin:0 0 15px}img{width:100%;display:block;border-radius:12px;border:1px solid #d4e0d3;box-shadow:0 10px 35px #355b4010}footer{font-size:11px;color:#7b8f82;margin-top:20px}</style><h1>SmartBox · ${title}</h1><p>同一业务，两种版式。手机端实际浏览器截图 · Demo / Mock Data</p><section>${images.map((img,i)=>`<article><h2>${names[i]}</h2><img src="data:image/png;base64,${img}"></article>`).join('')}</section><footer>版式可独立选择 · 所有操作仅为本地演示 · 未接入真实服务</footer></html>`);
      await board.screenshot({path:path.join(output,`${role}-comparison.png`),fullPage:true});
      await board.close();
    }
    fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(report,null,2));
    console.log(JSON.stringify({...report,screenshots:report.screenshots.length},null,2));
  } finally { await browser.close(); }
}
run().catch(error=>{console.error(error);process.exitCode=1;});
