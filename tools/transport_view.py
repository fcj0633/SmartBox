"""TASK-013 reproducible three-role browser integration; explicit project test reset only."""
from __future__ import annotations
import argparse,json,os,shutil,subprocess,sys,time,urllib.request,urllib.error,uuid
from pathlib import Path
from assets import environment,prepare as asset_prepare
from backend import ROOT,JAR,java_executable
from frontend import stop_owned,frontend_environment,node_command,FRONTEND
from simulator.config import prepare,confined,CREATE_FLAGS
from simulator.broker import Broker,port_open
from simulator.storage import atomic_json
from broker_profiles import backend_environment
from check_transport_view_contracts import validate_value
from urllib.parse import urlsplit

def require_test_environment(env):
 url=urlsplit(env.get('SMARTBOX_DB_URL','').removeprefix('jdbc:'))
 if (env.get('SPRING_PROFILES_ACTIVE')!='test' or url.hostname!='127.0.0.1'
     or url.path!='/smartbox_test' or env.get('SMARTBOX_DB_USER')!='smartbox_test_app'
     or env.get('SMARTBOX_MIGRATION_URL')!=env.get('SMARTBOX_DB_URL')
     or env.get('SMARTBOX_MIGRATION_USER')!='smartbox_test_migrator'):
  raise ValueError('Explicit local project test database and accounts required')

def integrate(config_path,directory):
 directory=confined(directory,ROOT/'artifacts')
 if directory.exists():raise ValueError('New evidence directory required')
 if any(port_open(p) for p in (1883,18080,15173)):raise ValueError('Required isolated port occupied; no process stopped')
 if not JAR.is_file():raise ValueError('Build backend first')
 env=environment(config_path,'test');require_test_environment(env)
 env.update(SERVER_ADDRESS='127.0.0.1',SERVER_PORT='18080',SMARTBOX_ALLOWED_ORIGINS='http://127.0.0.1:15173')
 directory.mkdir(parents=True);children=[];logs=[];broker=None;vite=None;success=False;checks=[];env_node=None
 def launch(name,args,variables=env,cwd=ROOT):
  log=(directory/(name+'.log')).open('wb');logs.append(log);child=subprocess.Popen(args,env=variables,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS);children.append(child);return child
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 def request(path,body=None,token=None):
  headers={'Content-Type':'application/json'}
  if token:headers['Authorization']='Bearer '+token
  req=urllib.request.Request('http://127.0.0.1:18080'+path,headers=headers,data=json.dumps(body).encode() if body is not None else None)
  with opener.open(req,timeout=10) as res:return json.load(res)
 def wait(predicate,label,timeout=60):
  end=time.monotonic()+timeout
  while time.monotonic()<end:
   try:
    if predicate():checks.append(label);print('PASS '+label,flush=True);return
   except (OSError,KeyError,ValueError):pass
   time.sleep(.25)
  raise AssertionError(label)
 try:
  reset_env=env.copy();reset_env.update(SMARTBOX_TEST_ALLOW_RESET='true',SPRING_MAIN_WEB_APPLICATION_TYPE='none',SMARTBOX_MQTT_ENABLED='false')
  reset=launch('test-reset',[java_executable(env),'-jar',str(JAR),'--smartbox.integration.reset=true','--spring.main.web-application-type=none'],reset_env,ROOT/'backend')
  if reset.wait(70):raise AssertionError('Guarded reset failed')
  seed=launch('asset-seed',[java_executable(env),'-jar',str(JAR),'--smartbox.assets.bootstrap=true'],env,ROOT/'backend')
  if seed.wait(70):raise AssertionError('Asset seed failed')
  private=directory/'mqtt.local.json';mqtt=prepare(private);broker=Broker(mqtt,directory/'broker');broker.start();env.update(backend_environment(mqtt))
  server=launch('backend',[java_executable(env),'-jar',str(JAR)],env,ROOT/'backend');wait(lambda:request('/actuator/health')['status']=='UP','isolated backend ready')
  for role in ('ENV','POSE'):
   run=directory/role;run.mkdir();child=launch(role,[sys.executable,str(ROOT/'tools/mqtt.py'),'node','--role',role,'--config',str(private),'--run-dir',str(run),'--events','0'])
   if role=='ENV':env_node=child
  fixture=json.loads(Path(env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'))
  users=[next(u for u in fixture['users'] if u['role']==role and u['box_ids']==['BOX-0001']) for role in ('LOGISTICS_OPERATOR','COURIER')]
  token=request('/api/v1/auth/login',{'username':users[0]['username'],'password':users[0]['password']})['data']['access_token']
  wait(lambda:request('/api/v1/boxes/BOX-0001/latest',token=token)['data']['online_mask']==3,'two independent MQTT nodes online')
  plugin=(FRONTEND/'node_modules/@vitejs/plugin-vue/dist/index.mjs').as_uri();vite=FRONTEND/'artifacts'/('transport-'+uuid.uuid4().hex+'.mjs');vite.parent.mkdir(exist_ok=True)
  vite.write_text('import vue from '+json.dumps(plugin)+';export default {plugins:[vue()],server:{proxy:{"/api":{target:"http://127.0.0.1:18080",changeOrigin:false},"/ws":{target:"ws://127.0.0.1:18080",ws:true,changeOrigin:false}}}};',encoding='utf-8')
  launch('frontend',node_command('dev')+['--config',str(vite),'--host','127.0.0.1','--port','15173','--strictPort'],frontend_environment(),FRONTEND);wait(lambda:port_open(15173),'isolated frontend ready')
  input_path=directory/'browser-input.local.json';atomic_json(input_path,{'url':'http://127.0.0.1:15173','users':users,'directory':str(directory)})
  try:
   browser=launch('browser',[shutil.which('node'),str(FRONTEND/'tests/transport-live.cjs'),str(input_path)],frontend_environment(),FRONTEND)
   deadline=time.monotonic()+180;event_started=False
   while browser.poll() is None and time.monotonic()<deadline:
    if not event_started and (directory/'active.json').exists():
     stop_owned(env_node);run=directory/'ENV-event';run.mkdir();launch('ENV-event',[sys.executable,str(ROOT/'tools/mqtt.py'),'node','--role','ENV','--config',str(private),'--run-dir',str(run),'--events','1']);event_started=True
    time.sleep(.2)
   if browser.poll() is None or browser.returncode:raise AssertionError('Real three-role browser failed; inspect private evidence')
  finally:input_path.unlink(missing_ok=True)
  for name,definition in [('monitoring','MonitoringResponse'),('enterprise','TasksResponse'),('riders','RidersResponse')]:validate_value(definition,json.loads((directory/('safe-'+name+'.json')).read_text(encoding='utf-8')))
  checks.append('three-role browser and actual response schemas')
  # The actual MQTT event is generated after start, not injected into the database.
  browser_result=json.loads((directory/'browser-summary.json').read_text(encoding='utf-8'))
  if not browser_result.get('passed'):raise AssertionError('Browser result incomplete')
  success=True
 finally:
  for child in reversed(children):stop_owned(child)
  if broker:broker.stop()
  for log in logs:log.close()
  if vite:vite.unlink(missing_ok=True)
  cleanup=all(child.poll() is not None for child in children) and not any(port_open(p) for p in (1883,18080,15173))
  atomic_json(directory/'summary.json',{'passed':success and cleanup,'checks':checks,'children_stopped':cleanup,'database':'smartbox_test','source':'independent simulator nodes via local MQTT','real_hardware':False})
  if not cleanup:raise AssertionError('Owned process cleanup failed')

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json');parser.add_argument('--run-dir',type=Path,required=True);parser.add_argument('--profile',choices=['test'],required=True);parser.add_argument('--allow-test-reset',action='store_true');args=parser.parse_args()
 try:
  if not args.allow_test_reset:raise ValueError('Explicit test reset consent required')
  asset_prepare('test',args.config);integrate(args.config,args.run_dir);return 0
 except (ValueError,OSError,KeyError,AssertionError,subprocess.SubprocessError):print('TASK-013 integration failed; inspect private evidence (credentials suppressed).',file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
