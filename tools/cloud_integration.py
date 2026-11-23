"""Real EMQX + isolated test backend + two node processes. No database deletion."""
from __future__ import annotations
import argparse,json,os,subprocess,sys,time,uuid,urllib.request,urllib.error
from pathlib import Path
from urllib.parse import urlsplit
from broker_profiles import ROOT,load_cloud,backend_environment
from backend import local_environment,java_executable,JAR
from frontend import stop_owned,CREATE_FLAGS
from simulator.broker import port_open
from simulator.storage import atomic_json,FileLock
from simulator.config import confined

def execute(config_path,directory,port=18080,browser=False):
    directory=confined(directory,ROOT/'artifacts');directory.mkdir(parents=True,exist_ok=False)
    config=load_cloud();env=local_environment(config_path,'test')
    url=urlsplit(env['SMARTBOX_DB_URL'].removeprefix('jdbc:'))
    if url.hostname not in ('127.0.0.1','localhost') or url.path!='/smartbox_test' or not env['SMARTBOX_DB_USER'].startswith('smartbox_test_'):raise ValueError('Project test database required')
    if port in (1883,8080,5173) or not 1024<=port<=65535 or port_open(port):raise ValueError('Use a free isolated backend port')
    env.update(backend_environment(config));env['SERVER_PORT']=str(port);env['SERVER_ADDRESS']='127.0.0.1'
    if browser:env['SMARTBOX_ALLOWED_ORIGINS']='http://127.0.0.1:15173'
    fixture=json.loads(Path(env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'))
    # Reserve a distinct range in the existing ledger, never reset node generations.
    from hardware import known_boots
    ledger=ROOT/'secrets/hardware/boot-reservations.json'
    with FileLock(ledger.parent/'provision.lock'):
        record=json.loads(ledger.read_text());observed=known_boots();state=directory/'state';state.mkdir()
        for role in ('ENV','POSE'):
            floor=max(record['reserved_through'][role],observed[role])
            if floor>4294967295-65536:raise ValueError('Generation range exhausted')
            record['reserved_through'][role]=floor+65536
            atomic_json(state/('BOX-0001-'+role+'.json'),{'node_id':'BOX-0001-'+role,'boot_id':floor,'boot_limit':floor+65536,'sentry_revision':0})
        atomic_json(ledger,record)
    config['state_dir']=str(state);private=directory/'mqtt.cloud.local.json';atomic_json(private,config)
    children=[];logs=[];checks=[];opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def launch(name,args,environment=None,cwd=ROOT):
        f=(directory/(name+'.log')).open('wb');logs.append(f)
        p=subprocess.Popen(args,cwd=cwd,env=environment,stdout=f,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS);children.append(p);return p
    def request(path,method='GET',body=None,token=None,key=None,expected=200):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        if key:headers['Idempotency-Key']=key
        req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
        try:
            with opener.open(req,timeout=12) as response:status=response.status;raw=response.read()
        except urllib.error.HTTPError as error:status=error.code;raw=error.read()
        if status!=expected:raise AssertionError('Unexpected HTTP status for '+path)
        return json.loads(raw)
    def wait(predicate,label,seconds=40):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            if any(p.poll() is not None for p in children):raise AssertionError('Own child exited')
            try:
                if predicate():checks.append({'check':label,'passed':True});print('PASS:',label,flush=True);return
            except (OSError,KeyError,urllib.error.URLError):pass
            time.sleep(.3)
        raise AssertionError(label)
    success=False;vite_config=None
    try:
        launch('backend',[java_executable(env),'-jar',str(JAR)],env,ROOT/'backend')
        wait(lambda:request('/actuator/health')['status']=='UP','real test backend and MySQL ready',60)
        for role in ('ENV','POSE'):
            launch(role,[sys.executable,str(ROOT/'tools/mqtt.py'),'node','--role',role,'--broker-profile','cloud','--config',str(private),'--run-dir',str(directory/role),'--stop-file',str(directory/'nodes.stop')])
        account=next(u for u in fixture['users'] if u['role']=='LOGISTICS_OPERATOR' and u['box_ids']==['BOX-0001'])
        token=request('/api/v1/auth/login','POST',{'username':account['username'],'password':account['password']})['data']['access_token']
        latest=lambda:request('/api/v1/boxes/BOX-0001/latest',token=token)['data']
        wait(lambda:latest()['online_mask']==3,'both independent cloud nodes persisted and queryable')
        for role in ('ENV','POSE'):
            def acknowledged(role=role):
                value=json.loads((directory/role/(role+'.status.json')).read_text());return value['ready'] and value.get('pending_events')==0
            wait(acknowledged,role+' event precisely acknowledged by backend after save')
        commands=[('output',{'kind':'light','on':True,'duration_s':2}),('gps_mode',{'mode':'ROUTE','seed':7}),('profile',{'profile_id':'CLOUD-P1','temp_min_c':0,'temp_max_c':40,'humi_min_pct':20,'humi_max_pct':80,'temp_hysteresis_c':1,'humi_hysteresis_pct':2}),('calibrate',{}),('sentry',{'on':True,'duration_s':4,'sentry_revision':1})]
        for kind,params in commands:
            body={'cmd':kind,'params':params};key='cloud-'+uuid.uuid4().hex
            op=request('/api/v1/boxes/BOX-0001/commands','POST',body,token,key,202)['data']['operation_id']
            if request('/api/v1/boxes/BOX-0001/commands','POST',body,token,key,202)['data']['operation_id']!=op:raise AssertionError('Idempotency mismatch')
            wait(lambda:request('/api/v1/boxes/BOX-0001/commands/'+op,token=token)['data']['status']=='SUCCEEDED',kind+' persisted MQTT execution and return receipt',18)
        # Independent read-only facts for a browser test; never persist a token or password.
        atomic_json(directory/'http-summary.json',{'online_mask':latest()['online_mask'],'commands':5,'test_database':'smartbox_test'})
        if browser:
            from frontend import FRONTEND,frontend_environment,node_command
            if port_open(15173):raise ValueError('Isolated browser port occupied')
            vue=(FRONTEND/'node_modules/@vitejs/plugin-vue/dist/index.mjs').as_uri()
            vite_config=FRONTEND/'artifacts'/('cloud-'+uuid.uuid4().hex+'.mjs');vite_config.parent.mkdir(exist_ok=True)
            target='http://127.0.0.1:'+str(port)
            vite_config.write_text("import vue from "+json.dumps(vue)+";export default {plugins:[vue()],server:{proxy:{'/api':{target:"+json.dumps(target)+",changeOrigin:false},'/ws':{target:"+json.dumps(target)+",ws:true,changeOrigin:false}}}};",encoding='utf-8')
            launch('frontend',node_command('dev')+['--config',str(vite_config),'--port','15173','--host','127.0.0.1','--strictPort'],frontend_environment(),FRONTEND)
            wait(lambda:port_open(15173),'isolated frontend ready',20)
            users=[next(u for u in fixture['users'] if u['role']==role and u['box_ids']==['BOX-0001']) for role in ('LOGISTICS_OPERATOR','LOGISTICS_VIEWER','DELIVERY_USER','COURIER')]
            browser_input=directory/'browser-input.local.json';atomic_json(browser_input,{'url':'http://127.0.0.1:15173','directory':str(directory),'users':users})
            try:
                browser_process=launch('browser',[__import__('shutil').which('node'),str(FRONTEND/'tests/cloud-smoke.cjs'),str(browser_input)],frontend_environment(),FRONTEND)
                if browser_process.wait(timeout=120):raise AssertionError('Real browser cloud check failed')
                checks.append({'check':'four-role browser/WS/output over real cloud','passed':True})
            finally:browser_input.unlink(missing_ok=True)
        success=True
    finally:
        (directory/'nodes.stop').touch();time.sleep(.5)
        for child in reversed(children):stop_owned(child)
        for log in logs:log.close()
        if vite_config:vite_config.unlink(missing_ok=True)
        cleanup=all(p.poll() is not None for p in children) and not port_open(port) and (not browser or not port_open(15173))
        atomic_json(directory/'summary.json',{'checks':checks,'passed':success and cleanup,'children_stopped':cleanup,'database_reset':False,'backend_port':port,'database':'smartbox_test'})
        if not cleanup:raise AssertionError('Own cloud integration cleanup failed')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json');p.add_argument('--report-dir',required=True,type=Path);p.add_argument('--allow-test',action='store_true');p.add_argument('--port',type=int,default=18080)
    p.add_argument('--browser',action='store_true');a=p.parse_args()
    try:
        if not a.allow_test:raise ValueError('Explicit --allow-test required; stop all other cloud node/backend clients first')
        execute(a.config,a.report_dir,a.port,a.browser);return 0
    except (ValueError,OSError,KeyError,AssertionError,subprocess.SubprocessError) as error:print('Cloud integration failed:',type(error).__name__,'; inspect private evidence (no credentials displayed).');return 1
if __name__=='__main__':raise SystemExit(main())
