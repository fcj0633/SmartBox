import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { readFileSync, writeFileSync, existsSync, renameSync } from 'node:fs';
import { join } from 'node:path';
const inputPath=process.env.SMARTBOX_BROWSER_INPUT;
if(!inputPath)throw new Error('Explicit private browser fixture required');
const fixture=JSON.parse(readFileSync(inputPath,'utf8'));
const directory=fixture.directory as string;
const checks:{check:string;passed:boolean;case:string}[]=[];
let sequence=0,complete=false;
const pause=(ms:number)=>new Promise(r=>setTimeout(r,ms));
async function control(action:string){
  const id=String(++sequence).padStart(3,'0');const target=join(directory,`control-${id}.request.json`);
  writeFileSync(target+'.tmp',JSON.stringify({action}));renameSync(target+'.tmp',target);
  const response=join(directory,`control-${id}.response.json`);const start=Date.now();
  while(!existsSync(response)){if(Date.now()-start>50000)throw new Error('Own test control timeout: '+action);await pause(100);}
  const value=JSON.parse(readFileSync(response,'utf8'));expect(value.ok).toBe(true);return value;
}
function record(key:string,check:string){checks.push({case:key,check,passed:true});writeFileSync(join(directory,'browser-progress.json'),JSON.stringify(checks,null,2));console.log('PASS: '+key+' '+check);}
async function login(page:Page,role:string,box=1){
  const user=fixture.users.find((u:any)=>u.role===role&&u.box_ids.join()===`BOX-000${box}`);
  if(!user)throw new Error('Missing test account for role');
  await page.goto('/login');await page.getByLabel('账号',{exact:true}).fill(user.username);await page.getByLabel('密码',{exact:true}).fill(user.password);
  await page.getByRole('button',{name:'进入工作台'}).click();await expect(page).toHaveURL(/\/boxes$/,{timeout:20000});
}
async function detail(page:Page){await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0001',exact:true})}).click();await expect(page.getByRole('heading',{name:'环境监测'})).toBeVisible({timeout:15000});}
async function command(page:Page,kind:string,accepted=true){
  await page.getByLabel('操作类型').selectOption(kind);await page.getByRole('button',{name:'检查并确认操作'}).click();await page.getByRole('button',{name:'确认发送',exact:true}).click();
  if(accepted)await expect(page.getByTestId('operation')).toBeVisible();
}
async function success(page:Page){await expect(page.getByTestId('operation').locator('header .badge')).toHaveText('成功',{timeout:18000});}
test.afterAll(()=>writeFileSync(join(directory,'browser-summary.json'),JSON.stringify({passed:complete,checks},null,2)));
test('real: browser authorization, five controls, recovery and 90-second silence',async({browser})=>{
  test.setTimeout(430000);
  const contexts:BrowserContext[]=[];
  const open=async(role:string,box=1)=>{const c=await browser.newContext({viewport:{width:1440,height:900}});contexts.push(c);const p=await c.newPage();await login(p,role,box);return p;};
  try {
    const page=await open('LOGISTICS_OPERATOR');await detail(page);
    await expect(page.getByText('尚未完成判断')).toBeVisible();await expect(page.getByRole('heading',{name:'节点状态'})).toBeVisible();
    await expect(page.getByText('实时连接已建立')).toBeVisible({timeout:15000});
    expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);
    await page.screenshot({path:join(directory,'operator-1440.png'),fullPage:true});
    record('F01/F03/F04/F06','real login, dual-node detail, unknown alarm/door, MOCK and memory-only session');
    for(const kind of ['output','gps_mode','profile','calibrate','sentry']){
      await command(page,kind);await success(page);
      await expect(page.getByTestId('operation').locator('tbody tr')).toHaveCount(kind==='sentry'?2:1);
      record('F07','browser command '+kind+' executes on real simulated process and saved outcome returns');
    }
    const operationId=(await page.getByTestId('operation').locator('.operation-id').textContent())!.trim();
    await page.setViewportSize({width:1280,height:720});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.screenshot({path:join(directory,'operator-1280.png'),fullPage:true});record('F13','real desktop layout at 1440x900 and 1280x720 has no horizontal overflow');

    // Drop the first POST response after the real backend committed. UI must retry the exact same request/key.
    await page.getByLabel('操作类型').selectOption('output');let dropped=false;const keys:string[]=[];let realOperation='';
    await page.route('**/api/v1/boxes/BOX-0001/commands',async route=>{
      if(route.request().method()!=='POST'){await route.continue();return;}
      keys.push(route.request().headers()['idempotency-key']!);
      const response=await route.fetch();if(!dropped){dropped=true;realOperation=(await response.json()).data.operation_id;await route.abort('failed');}else await route.fulfill({response});
    });
    await command(page,'output',false);await expect(page.getByRole('button',{name:'使用原请求重试'})).toBeVisible();await page.getByRole('button',{name:'使用原请求重试'}).click();await expect(page.getByTestId('operation').locator('.operation-id')).toHaveText(realOperation);await success(page);
    expect(keys.length).toBe(2);expect(keys[0]===keys[1]).toBe(true);expect((await page.getByTestId('operation').locator('.operation-id').textContent())!.trim()===realOperation).toBe(true);
    await page.unroute('**/api/v1/boxes/BOX-0001/commands');record('F08','lost real POST response recovers with identical key and original operation');

    const delivery=await open('DELIVERY_USER');const paths:string[]=[];const frames:string[]=[];
    delivery.on('request',r=>{if(r.url().includes('/api/'))paths.push(new URL(r.url()).pathname);});
    delivery.on('websocket',ws=>{if(new URL(ws.url()).pathname==='/ws')ws.on('framereceived',frame=>{frames.push(String(frame.payload));});});
    await detail(delivery);await expect(delivery.getByRole('heading',{name:'姿态监测'})).toHaveCount(0);await expect(delivery.getByRole('heading',{name:'节点状态'})).toHaveCount(0);await expect(delivery.getByRole('heading',{name:'远程控制'})).toHaveCount(0);
    await pause(2500);expect(paths.some(p=>p.endsWith('/nodes'))).toBe(false);
    for(const raw of frames){const m=JSON.parse(raw);expect(['node_status','command_status'].includes(m.type)).toBe(false);if(m.type==='snapshot'){expect('pose' in m.data||'sentry' in m.data||'evidence' in m.data).toBe(false);}}
    await delivery.screenshot({path:join(directory,'delivery-1440.png'),fullPage:true});record('F11','real delivery HTTP/WS and page omit pose, evidence, diagnostics and controls');
    const viewer=await open('LOGISTICS_VIEWER');await detail(viewer);await expect(viewer.getByRole('heading',{name:'远程控制'})).toHaveCount(0);
    const courier=await open('COURIER');await detail(courier);expect(await courier.locator('#command-kind option').allTextContents()).toEqual(['布撤防']);
    await command(courier,'sentry');await success(courier);record('F02/F07','four real roles including courier sentry-only control');
    // Same application session: RouterLink click rather than full navigation that would clear memory auth.
    await page.evaluate(()=>{const a=document.createElement('a');a.href='/boxes/BOX-0002';a.id='test-cross-box';a.textContent='cross';document.body.appendChild(a);});
    await page.getByRole('link',{name:'返回物流箱列表'}).click();await expect(page).toHaveURL(/boxes$/);
    expect(await page.getByRole('heading',{name:'BOX-0002',exact:true}).count()).toBe(0);
    await detail(page);record('F02','authorized navigation does not include another box');
    await control('revoke_viewer');await expect(viewer).toHaveURL(/login/,{timeout:15000});await expect(viewer.getByRole('heading',{name:'环境监测'})).toHaveCount(0);record('F12','real MySQL authorization revocation closes/clears current viewer session');

    await command(page,'sentry');await control('suspend_arming_pose');
    await expect(page.getByTestId('operation').locator('header .badge')).toHaveText('部分成功',{timeout:14000});
    const partialId=(await page.getByTestId('operation').locator('.operation-id').textContent())!.trim();
    await control('resume_pose');await pause(1000);await page.getByRole('button',{name:'刷新结果'}).click();
    await expect(page.getByTestId('operation').locator('header .badge')).toHaveText('部分成功');
    expect((await page.getByTestId('operation').locator('.operation-id').textContent())!.trim()).toBe(partialId);
    await expect(page.getByTestId('operation').locator('tbody tr').filter({hasText:'BOX-0001-POSE'})).toContainText('成功',{timeout:5000});
    record('F09','real partial sentry outcome stays fixed after late node facts');

    await control('backend_stop');await expect(page.getByText('实时连接中断')).toBeVisible({timeout:10000});
    await control('backend_start');await expect(page.getByText('实时连接已建立')).toBeVisible({timeout:40000});await expect(page.getByRole('heading',{name:'环境监测'})).toBeVisible();record('F05/F10','real backend interruption recovers browser WS and REST state');
    await control('broker_restart');await expect(page.locator('tbody tr').filter({hasText:'BOX-0001-POSE'}).first()).toContainText('在线',{timeout:25000});record('F05','real Broker restart resumes node and backend communication');

    await control('suspend_pose');
    await expect(page.getByText('已过期',{exact:true}).first()).toBeVisible({timeout:22000});
    // LWT from keepalive may already mark offline; actual wall-clock silence must still exceed 90 seconds.
    await pause(93000);
    await page.getByRole('button',{name:'刷新数据'}).click();await expect(page.locator('tbody tr').filter({hasText:'BOX-0001-POSE'}).first()).toContainText('离线',{timeout:8000});
    const observed=await control('resume_pose');expect(observed.observed_silence_s>=90).toBe(true);
    await expect(page.locator('tbody tr').filter({hasText:'BOX-0001-POSE'}).first()).toContainText('在线',{timeout:30000});
    writeFileSync(join(directory,'silence.json'),JSON.stringify(observed));record('F04/F05','real >90 second suspended node silence shows stale/offline and recovers without touching shared MySQL');
    await page.getByRole('button',{name:'退出登录'}).click();await expect(page).toHaveURL(/login/);expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);
    await login(page,'DELIVERY_USER');await detail(page);await expect(page.getByRole('heading',{name:'远程控制'})).toHaveCount(0);await page.reload();await expect(page).toHaveURL(/login/);record('F01/F10/F11','logout, account switch and refresh clear previous operator data and session');
    expect(operationId.length>0).toBe(true);complete=true;record('F14','browser acceptance keeps credentials in private input and disables trace/HAR/video');
  }catch(error){
    for(let i=0;i<contexts.length;i++)for(const p of contexts[i]!.pages()){
      // No login screenshots: even masked credentials are outside evidence scope.
      if(new URL(p.url()).pathname.startsWith('/boxes'))await p.screenshot({path:join(directory,`failure-page-${i}.png`),fullPage:true}).catch(()=>{});
    }
    throw error;
  }finally{for(const context of contexts)await context.close();}
});
test('targeted: real URL denial and nonempty authorized WS Schema validation',async({browser})=>{
  test.setTimeout(65000);await control('restore_viewer');
  const operator=await browser.newContext(),delivery=await browser.newContext(),viewer=await browser.newContext();
  try{
    const page=await operator.newPage();const loginReply=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith('/auth/login'));
    await login(page,'LOGISTICS_OPERATOR');const token=(await(await loginReply).json()).data.access_token;
    await detail(page);
    const denied=await page.request.get('/api/v1/boxes/BOX-0002/latest',{headers:{Authorization:'Bearer '+token}});expect(denied.status()).toBe(403);
    await page.evaluate(()=>{history.pushState({},'','/boxes/BOX-0002');dispatchEvent(new PopStateEvent('popstate'));});
    await expect(page.getByRole('alert')).toContainText('无权访问');await expect(page.getByRole('heading',{name:'环境监测'})).toHaveCount(0);
    record('F02','real server HTTP denies another box; same-session URL change removes detail before ungranted read');
    const p=await delivery.newPage();const frames:any[]=[];
    p.on('websocket',ws=>{if(new URL(ws.url()).pathname==='/ws')ws.on('framereceived',frame=>{try{const m=JSON.parse(String(frame.payload));if(m.type!=='error')frames.push(m);}catch{/* protocol control frames carry no business fields */}});});
    await login(p,'DELIVERY_USER');await detail(p);
    await expect.poll(()=>frames.some(m=>m.type==='snapshot'),{timeout:10000}).toBe(true);
    writeFileSync(join(directory,'delivery-frames.json'),JSON.stringify(frames));
    const result=await control('validate_delivery_frames');expect(result.frames>0).toBe(true);
    record('F11/F12','nonempty real delivery WS and four-role HTTP independently pass current effective Schema');
    const readOnly=await viewer.newPage();let closed=0;
    readOnly.on('websocket',ws=>{if(new URL(ws.url()).pathname==='/ws')ws.on('close',()=>closed++);});
    await login(readOnly,'LOGISTICS_VIEWER');await detail(readOnly);await expect(readOnly.getByText('实时连接已建立')).toBeVisible();
    // Ignore the intentional list->detail connection replacement, then create an operator command.
    await pause(500);closed=0;
    await page.getByRole('link',{name:'返回物流箱列表'}).click();await detail(page);await command(page,'output');await success(page);await pause(1500);
    expect(closed).toBe(0);await expect(readOnly.getByText('实时连接已建立')).toBeVisible();
    record('F11/F12','read-only real WS remains connected while another account executes a command');
    complete=true;
  }finally{await operator.close();await delivery.close();await viewer.close();}
});
