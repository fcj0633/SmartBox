import { test, expect, type Page } from '@playwright/test';
const sample=(value:unknown)=>({value,valid:true,stale:false,freshness:'FRESH',observed_at:Date.now(),received_at:Date.now(),time_quality:'SNTP',src:'MOCK'});
function box(id='BOX-0001') {return {box_id:id,online_mask:3,connectivity:'ONLINE',env:sample({temp_c:23,humi_pct:50,lux:200}),pose:sample({tilt_deg:5,acc_g:[0,0,1]}),loc:sample({lat:31,lon:121,src:'MOCK'}),door:{state:'UNKNOWN',confidence:'INSUFFICIENT'},alarm:{assessment:'UNKNOWN',active:null,level:null},sentry:{state:'DISARMED',revision:1}};}
async function setup(page:Page,role='LOGISTICS_OPERATOR',ids=['BOX-0001','BOX-0002']) {
  await page.addInitScript(()=>{ class WS {static OPEN=1;readyState=1;onopen:any;onclose:any;onmessage:any;onerror:any;constructor(){setTimeout(()=>this.onopen?.(),20);}send(){}close(){this.readyState=3;}};(window as any).WebSocket=WS; });
  await page.route('**/api/v1/**',async route=>{
    const p=new URL(route.request().url()).pathname;let data:any;
    if(p.endsWith('/auth/login')) data={access_token:'isolated-memory-only',expires_at:Date.now()+1800000,role};
    else if(p.endsWith('/me')) data={username:'isolated-user',role,box_ids:ids};
    else if(p.endsWith('/ws-ticket')) data={ticket:'isolated-ticket',expires_at:Date.now()+30000};
    else if(p.endsWith('/nodes'))data=[{node_id:'BOX-0001-ENV',role:'ENV',caps:71,online:true,required:true,boot_id:1,last_received_at:Date.now()}];
    else if(p.endsWith('/boxes'))data=ids.map(x=>box(x));
    else data=box(p.split('/')[4]);
    if(role==='DELIVERY_USER')for(const b of Array.isArray(data)?data:[data]){if(b?.box_id){delete b.pose;delete b.sentry;}}
    await route.fulfill({json:{data,request_id:'isolated-case'}});
  });
}
async function login(page:Page){await page.goto('/login');await page.getByLabel('账号',{exact:true}).fill('isolated');await page.getByLabel('密码',{exact:true}).fill('not-real');await page.getByRole('button',{name:'进入工作台'}).click();await expect(page).toHaveURL(/\/boxes$/);}
test('isolated: empty authorized list and refresh require login',async({page})=>{await setup(page,'LOGISTICS_VIEWER',[]);await login(page);await expect(page.getByText('暂无授权箱体')).toBeVisible();await page.reload();await expect(page).toHaveURL(/login/);expect(await page.evaluate(()=>localStorage.length+sessionStorage.length)).toBe(0);});
test('isolated: delivery fields absent and cross box denied',async({page})=>{await setup(page,'DELIVERY_USER',['BOX-0001']);await login(page);await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0001'})}).click();await expect(page.getByText('尚未完成判断')).toBeVisible();await expect(page.getByRole('heading',{name:'节点状态'})).toHaveCount(0);await expect(page.getByRole('heading',{name:'远程控制'})).toHaveCount(0);await expect(page.getByRole('heading',{name:'姿态监测'})).toHaveCount(0);await page.goto('/boxes/BOX-0002');/* fresh page intentionally logs out */await expect(page).toHaveURL(/login/);});
for(const size of [{width:1440,height:900},{width:1280,height:720}])test(`isolated: desktop ${size.width}, unknown states and safe text`,async({page})=>{await setup(page);await page.setViewportSize(size);await login(page);await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0001'})}).click();await expect(page.getByRole('heading',{name:'环境监测'})).toBeVisible();await expect(page.getByText('尚未完成判断')).toBeVisible();await expect(page.getByRole('heading',{name:'远程控制'})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.getByLabel('操作类型').selectOption('profile');await page.getByLabel('画像标识').fill('<img src=x onerror=alert(1)>');await page.getByRole('button',{name:'检查并确认操作'}).click();await expect(page.getByRole('dialog')).toBeVisible();await expect(page.locator('img')).toHaveCount(0);await page.screenshot({path:`../artifacts/task006/isolated-${size.width}.png`,fullPage:true});});
test('isolated: 503 preserves last data with explicit stale sync, forbidden clears it',async({page})=>{await setup(page);await login(page);await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0001'})}).click();await expect(page.getByRole('heading',{name:'环境监测'})).toBeVisible();await page.route('**/latest',route=>route.fulfill({status:503,json:{request_id:'safe-failure'}}));await page.getByRole('button',{name:'刷新数据'}).click();await expect(page.getByRole('alert')).toContainText('服务暂不可用');await expect(page.getByText('同步中断 · 最后数据').first()).toBeVisible();await page.route('**/latest',route=>route.fulfill({status:403,json:{request_id:'safe-denied'}}));await page.getByRole('button',{name:'刷新数据'}).click();await expect(page.getByRole('alert')).toContainText('无权访问');await expect(page.getByRole('heading',{name:'环境监测'})).toHaveCount(0);});
test('isolated: wrong login stays on form and does not persist password',async({page})=>{
  await setup(page);await page.route('**/auth/login',route=>route.fulfill({status:401,json:{request_id:'wrong-login'}}));await page.goto('/login');await page.getByLabel('账号',{exact:true}).fill('invalid');await page.getByLabel('密码',{exact:true}).fill('not-a-real-password');await page.getByRole('button',{name:'进入工作台'}).click();await expect(page.getByRole('alert')).toContainText('登录信息无效');await expect(page).toHaveURL(/login/);await expect(page.getByLabel('密码',{exact:true})).toHaveValue('');
});
test('isolated: expires_at ends the memory session',async({page})=>{
  await setup(page);await page.route('**/auth/login',route=>route.fulfill({json:{data:{access_token:'isolated-short-session',expires_at:Date.now()+2500,role:'LOGISTICS_VIEWER'},request_id:'short-session'}}));await login(page);await expect(page).toHaveURL(/login/,{timeout:5000});await expect(page.getByRole('alert')).toContainText('已过期');
});
test('isolated: old box response cannot overwrite another box, 404 clears detail',async({page})=>{
  await setup(page);await login(page);await page.route('**/boxes/BOX-0001/latest',async route=>{await new Promise(r=>setTimeout(r,1800));const old=box();old.env.value={temp_c:11};await route.fulfill({json:{data:old,request_id:'old-box'}}).catch(()=>{});});
  await page.route('**/boxes/BOX-0002/latest',route=>{const newer=box('BOX-0002');newer.env.value={temp_c:42};return route.fulfill({json:{data:newer,request_id:'new-box'}});});
  await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0001'})}).click();await page.getByRole('link',{name:'返回物流箱列表'}).click();await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0002'})}).click();await expect(page.getByRole('heading',{name:'BOX-0002',exact:true})).toBeVisible();await expect(page.locator('.sample-card').first()).toContainText('42');await page.waitForTimeout(2000);await expect(page.locator('.sample-card').first()).toContainText('42');
  await page.route('**/boxes/BOX-0002/latest',route=>route.fulfill({status:404,json:{request_id:'missing-resource'}}));await page.getByRole('button',{name:'刷新数据'}).click();await expect(page.getByRole('alert')).toContainText('资源不存在');await expect(page.getByRole('heading',{name:'环境监测'})).toHaveCount(0);
});
test('isolated: route modification in same session is denied before fetching ungranted box',async({page})=>{
  await setup(page,'LOGISTICS_VIEWER',['BOX-0001']);await login(page);let forbiddenReads=0;page.on('request',r=>{if(r.url().includes('/BOX-0002/'))forbiddenReads++;});
  await page.evaluate(()=>{history.pushState({},'','/boxes/BOX-0002');dispatchEvent(new PopStateEvent('popstate'));});await expect(page.getByRole('alert')).toContainText('无权访问');expect(forbiddenReads).toBe(0);await expect(page.getByRole('heading',{name:'环境监测'})).toHaveCount(0);
});
test('isolated: known unsynced target disables control with explicit reason',async({page})=>{
  await setup(page);await login(page);await page.route('**/latest',route=>{const value=box();value.env.time_quality='UNSYNCED';return route.fulfill({json:{data:value,request_id:'unsynced-clock'}});});
  await page.getByRole('link').filter({has:page.getByRole('heading',{name:'BOX-0001'})}).click();await expect(page.getByText('目标节点未校时，暂不能发送控制；请等待实际校时状态恢复。')).toBeVisible();await expect(page.getByRole('button',{name:'检查并确认操作'})).toBeDisabled();
});
test('isolated: keyboard login and logout have labeled reachable controls',async({page})=>{
  await setup(page);await page.goto('/login');await page.getByLabel('账号',{exact:true}).focus();await page.keyboard.type('isolated');await page.keyboard.press('Tab');await expect(page.getByLabel('密码',{exact:true})).toBeFocused();await page.keyboard.type('not-real');await page.keyboard.press('Tab');await expect(page.getByRole('button',{name:'进入工作台'})).toBeFocused();await page.keyboard.press('Enter');await expect(page).toHaveURL(/boxes$/);await page.getByRole('button',{name:'退出登录'}).focus();await page.keyboard.press('Enter');await expect(page).toHaveURL(/login/);
});
