/* Real sample-driven integration only; no route mocks, HAR, trace or token output. */
const {chromium}=require('playwright-core');
const fs=require('node:fs/promises');const path=require('node:path');
(async()=>{
 const input=JSON.parse(await fs.readFile(process.argv[2],'utf8'));const browser=await chromium.launch({headless:true,args:['--no-proxy-server']});const results=[];
 try{
  for(const user of input.users){
   const context=await browser.newContext({viewport:{width:1440,height:900}});const page=await context.newPage();let alarms=0;
   page.on('websocket',ws=>ws.on('framereceived',frame=>{try{const m=JSON.parse(String(frame.payload));if(m.type==='box_event'&&m.data?.type==='ALARM')alarms++;}catch{}}));
   await page.goto(input.url+'/login');await page.locator('#username').fill(user.username);await page.locator('#password').fill(user.password);await page.locator('.login-submit').click();await page.waitForURL('**/boxes');
   const response=page.waitForResponse(r=>r.url().includes('/BOX-0001/latest')&&r.status()===200);await page.locator('a[href="/boxes/BOX-0001"]').click();const data=(await(await response).json()).data;
   if(data.door.state!=='OPEN'||data.door.confidence!=='HIGH')throw Error('Real dual-source conclusion missing');
   await page.getByText('实时通道已连接',{exact:true}).waitFor();
   if(user.role==='LOGISTICS_OPERATOR'){
    await fs.writeFile(path.join(input.directory,'browser-ready.json'),JSON.stringify({ready:true}));
    await page.getByText('存在警告级活动告警',{exact:true}).waitFor({timeout:30000});
    await page.getByText('无活动告警',{exact:true}).waitFor({timeout:20000});
    if(alarms<2)throw Error('Start/recovery WS notices missing');
    await page.screenshot({path:path.join(input.directory,'rule-result.png'),fullPage:true});
   }
   if(user.role==='DELIVERY_USER'&&['pose','sentry','evidence','node_diagnostics'].some(k=>k in data))throw Error('Forbidden delivery projection');
   results.push({role:user.role,door:'OPEN',confidence:'HIGH',alarm_notices:alarms,passed:true});await context.close();
  }
  await fs.writeFile(path.join(input.directory,'browser-summary.json'),JSON.stringify({passed:true,results},null,2));console.log('PASS real rule browser, alarm recovery and role projection');
 }finally{await browser.close();}
})().catch(()=>{console.error('Rule browser failed; credentials suppressed.');process.exitCode=1;});
