"""Real TASK-011 HTTP checks, local project databases only; no broker or UI."""
from __future__ import annotations
import json,os,shutil,subprocess,time,urllib.request,urllib.error
from pathlib import Path
from urllib.parse import urlsplit
from backend import ROOT,JAR,java_executable
from frontend import stop_owned
from simulator.config import confined,CREATE_FLAGS
from simulator.broker import port_open
from check_assets_contracts import validate_value

def query(env,sql):
    url=urlsplit(env['SMARTBOX_DB_URL'].removeprefix('jdbc:'));exe=shutil.which('mysql')
    if not exe or url.hostname!='127.0.0.1' or url.path not in ('/smartbox_dev','/smartbox_test'):raise ValueError('Local project MySQL client required')
    variables=env.copy();variables['MYSQL_PWD']=env['SMARTBOX_DB_PASSWORD']
    result=subprocess.run([exe,'--protocol=TCP','--host=127.0.0.1','--port='+str(url.port),'--user='+env['SMARTBOX_DB_USER'],'--database='+url.path[1:],'--default-character-set=utf8mb4','--batch','--raw','--skip-column-names'],input=sql,text=True,encoding='utf-8',stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=variables,timeout=15)
    if result.returncode:raise ValueError('Project database inspection failed; diagnostics suppressed')
    return result.stdout.strip()

def protected_state(env):
    return {'users':query(env,"SELECT id,SHA2(CONCAT_WS(CHAR(31),username,password_hash,role_code,enabled,created_at,updated_at),256) FROM users ORDER BY id"),
            'grants':query(env,"SELECT user_id,box_id,created_at FROM user_box_access ORDER BY user_id,box_id"),
            'tables':{name:int(query(env,'SELECT COUNT(*) FROM '+name)) for name in ('telemetry','events','nodes')}}

def execute(env,profile,port,directory):
    directory=confined(directory,ROOT/'artifacts');directory.mkdir(parents=True,exist_ok=False)
    if not 1024<=port<=65535 or port in (1883,8080,5173) or port_open(port):raise ValueError('Free isolated HTTP port required; existing processes preserved')
    env=env.copy();env.update(SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(port),SMARTBOX_MQTT_ENABLED='false')
    fixture=json.loads(Path(env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'))
    def account(role,box):return next(u for u in fixture['users'] if u['role']==role and u['box_ids']==[box])
    operator=account('LOGISTICS_OPERATOR','BOX-0001');rider=account('COURIER','BOX-0001');other=account('DELIVERY_USER','BOX-0001')
    rider_id=int(query(env,"SELECT id FROM users WHERE BINARY username=UNHEX('"+rider['username'].encode().hex()+"')"))
    before=protected_state(env);checks=[];process=None;token=None;changed=False;success=False
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));base='http://127.0.0.1:'+str(port)+'/api/v1'
    def request(method,path,body=None,auth=None,status=200,definition=None):
        headers={'Content-Type':'application/json'}
        if auth:headers['Authorization']='Bearer '+auth
        req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
        try:
            with opener.open(req,timeout=15) as response:code=response.status;value=json.load(response)
        except urllib.error.HTTPError as error:code=error.code;value=json.load(error)
        if code!=status:raise AssertionError('Unexpected HTTP status '+str(code)+' on '+path)
        if definition:validate_value(definition,value)
        checks.append({'method':method,'path':path,'status':code,'definition':definition});return value
    def login(user):return request('POST','/auth/login',{'username':user['username'],'password':user['password']})['data']['access_token']
    log=(directory/'server.log').open('wb')
    try:
        process=subprocess.Popen([java_executable(env),'-jar',str(JAR)],env=env,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS)
        until=time.monotonic()+90
        while not port_open(port):
            if process.poll() is not None or time.monotonic()>until:raise AssertionError('Own backend failed to start')
            time.sleep(.2)
        token=login(operator);rider_token=login(rider);other_token=login(other)
        enterprise=request('GET','/enterprise/boxes',auth=token,definition='EnterpriseResponse')['data']
        box=next(b for b in enterprise['boxes'] if b['box_id']=='BOX-0001')
        if box['rider_user_id'] is not None:raise ValueError('Smoke requires unassigned demo box; no existing assignment overwritten')
        me=request('GET','/me',auth=rider_token)['data'];old_boxes=request('GET','/boxes',auth=rider_token)['data'];request('GET','/boxes/BOX-0001/latest',auth=rider_token)
        request('GET','/rider/boxes',auth=rider_token,definition='RiderResponse')
        a=request('POST','/enterprise/boxes/BOX-0001/assign',{'rider_user_id':rider_id},token,definition='AssignmentResponse');changed=True
        if a['data']!=request('POST','/enterprise/boxes/BOX-0001/assign',{'rider_user_id':rider_id},token,definition='AssignmentResponse')['data']:raise AssertionError('Repeat changed assignment')
        boxes=request('GET','/rider/boxes',auth=rider_token,definition='RiderResponse')['data']
        if not any(b['box_id']=='BOX-0001' and b['source']=='ASSIGNED' for b in boxes) or not any(b['box_id']=='BOX-DEMO-02' and b['source']=='OWNED' for b in boxes):raise AssertionError('Rider asset sources missing')
        request('POST','/enterprise/boxes/BOX-0001/unassign',auth=other_token,status=403,definition='ErrorResponse')
        request('POST','/enterprise/boxes/BOX-DEMO-02/assign',{'rider_user_id':rider_id},token,status=403,definition='ErrorResponse')
        if profile=='test':
            b=login(account('LOGISTICS_OPERATOR','BOX-0002'));request('POST','/enterprise/boxes/BOX-0001/assign',{'rider_user_id':rider_id},b,status=403,definition='ErrorResponse')
            other_rider=account('COURIER','BOX-0002');other_id=int(query(env,"SELECT id FROM users WHERE BINARY username=UNHEX('"+other_rider['username'].encode().hex()+"')"))
            request('POST','/enterprise/boxes/BOX-0001/assign',{'rider_user_id':other_id},token,status=409,definition='ErrorResponse')
        request('POST','/enterprise/boxes/BOX-0001/unassign',auth=token,definition='UnassignResponse');changed=False
        request('POST','/enterprise/boxes/BOX-0001/unassign',auth=token,definition='UnassignResponse')
        if any(b['box_id']=='BOX-0001' for b in request('GET','/rider/boxes',auth=rider_token,definition='RiderResponse')['data']):raise AssertionError('Reclaimed box still visible')
        if me!=request('GET','/me',auth=rider_token)['data'] or old_boxes!=request('GET','/boxes',auth=rider_token)['data']:raise AssertionError('Legacy device authorization changed')
        request('GET','/boxes/BOX-0001/latest',auth=rider_token)
        after=protected_state(env)
        if before['users']!=after['users'] or before['grants']!=after['grants'] or any(after['tables'][t]<v for t,v in before['tables'].items()):raise AssertionError('Existing data changed unexpectedly')
        success=True
    finally:
        restored=True
        if changed and token:
            try:
                current=request('GET','/enterprise/boxes',auth=token,definition='EnterpriseResponse')['data']['boxes']
                if next(b for b in current if b['box_id']=='BOX-0001')['rider_user_id']!=rider_id:raise AssertionError('Concurrent assignment changed; no cleanup overwrite')
                request('POST','/enterprise/boxes/BOX-0001/unassign',auth=token,definition='UnassignResponse')
            except Exception:restored=False
        if process:stop_owned(process)
        log.close();clean=process is not None and process.poll() is not None and not port_open(port)
        (directory/'summary.json').write_text(json.dumps({'passed':success and clean and restored,'profile':profile,'database':'smartbox_'+profile,'checks':checks,'checks_count':len(checks),'server_stopped':clean,'demo_assignment_restored':restored,'legacy_identity_and_grants_unchanged':success},ensure_ascii=False,indent=2),encoding='utf-8')
        if not clean or not restored:raise AssertionError('Smoke cleanup requires inspection')
    print('PASS: '+profile+' real HTTP checks='+str(len(checks))+'; asset schemas and legacy grants preserved; own server stopped')
    return 0
