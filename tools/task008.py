"""TASK-008 real rule software integration; explicit test reset and isolated ports."""
from __future__ import annotations
import argparse,copy,json,os,subprocess,sys,time,urllib.request,urllib.error,uuid
from pathlib import Path
from urllib.parse import urlsplit
from backend import local_environment,java_executable,JAR
from broker_profiles import ROOT,load_cloud,backend_environment
from simulator.config import prepare,confined,CREATE_FLAGS
from simulator.storage import atomic_json
from simulator.broker import Broker,port_open
from frontend import stop_owned,FRONTEND,frontend_environment,node_command

def execute(config_path,directory,cloud=False):
 directory=confined(directory,ROOT/'artifacts');directory.mkdir(parents=True,exist_ok=False)
 env=local_environment(config_path,'test');url=urlsplit(env['SMARTBOX_DB_URL'].removeprefix('jdbc:'))
 if url.hostname not in ('localhost','127.0.0.1') or url.path!='/smartbox_test' or env['SMARTBOX_DB_USER']!='smartbox_test_app':raise ValueError('Project test database required')
 ports=[18080,15173]+([] if cloud else [1883])
 if any(port_open(p) for p in ports):raise ValueError('Own required port occupied; nothing stopped')
 env.update(SERVER_PORT='18080',SERVER_ADDRESS='127.0.0.1',SMARTBOX_ALLOWED_ORIGINS='http://127.0.0.1:15173')
 children=[];logs=[];nodes={};checks=[];broker=None;vite_config=None;success=False
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 def launch(name,args,environment=None,cwd=ROOT):
  log=(directory/(name+'.log')).open('wb');logs.append(log);p=subprocess.Popen(args,cwd=cwd,env=environment,stdout=log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS);children.append(p);return p
 def http(path,body=None,token=None):
  headers={'Content-Type':'application/json'}
  if token:headers['Authorization']='Bearer '+token
  req=urllib.request.Request('http://127.0.0.1:18080'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
  with opener.open(req,timeout=10) as response:return json.load(response)
 def wait(predicate,label,timeout=35):
  end=time.monotonic()+timeout
  while time.monotonic()<end:
   try:
    if predicate():checks.append({'check':label,'passed':True});print('PASS '+label,flush=True);return
   except (OSError,urllib.error.URLError,KeyError,json.JSONDecodeError):pass
   time.sleep(.2)
  checks.append({'check':label,'passed':False});raise AssertionError(label)
 def node(role,case=None,barrier=None):
  if role in nodes:
   stop_owned(nodes[role][0]);nodes[role][1].touch()
  run_dir=directory/(role+'-'+str(time.time_ns()));run_dir.mkdir();stop=run_dir/'stop.request'
  args=[sys.executable,str(ROOT/'tools/mqtt.py'),'node','--role',role,'--broker-profile','cloud' if cloud else 'local','--config',str(private),'--run-dir',str(run_dir),'--stop-file',str(stop),'--events','0']
  if case:args+=['--scenario','sample-rules','--rule-case',case]
  if barrier:args+=['--rule-start-file',str(barrier)]
  process=launch(role+'-'+str(len(children)),args);nodes[role]=(process,stop,run_dir)
  wait(lambda:json.loads((run_dir/(role+'.status.json')).read_text()).get('ready'),role+' connected/subscribe '+str(case),20)
 def node_acked(role):
  value=json.loads((nodes[role][2]/(role+'.status.json')).read_text());return value.get('rule_complete') and value.get('pending_events')==0 and value.get('dropped')==0
 try:
  reset_env=env.copy();reset_env.update(SMARTBOX_TEST_ALLOW_RESET='true',SMARTBOX_MQTT_ENABLED='false',SPRING_MAIN_WEB_APPLICATION_TYPE='none')
  reset=launch('test-reset',[java_executable(reset_env),'-jar',str(JAR),'--smartbox.integration.reset=true','--spring.main.web-application-type=none'],reset_env,ROOT/'backend')
  if reset.wait(timeout=60):raise AssertionError('Guarded test reset failed')
  if cloud:
   config=load_cloud();config['state_dir']=str(directory/'state')
   from cloud_mqtt import reserve_state
   reserve_state(config)
  else:config=prepare(directory/'mqtt.local.json')
  private=directory/'mqtt-run.local.json';atomic_json(private,config);env.update(backend_environment(config))
  if not cloud:broker=Broker(config,directory/'broker');broker.start()
  launch('backend',[java_executable(env),'-jar',str(JAR)],env,ROOT/'backend');wait(lambda:http('/actuator/health')['status']=='UP','test backend ready',70)
  fixture=json.loads(Path(env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'));roles=('LOGISTICS_OPERATOR','LOGISTICS_VIEWER','DELIVERY_USER','COURIER');users=[next(u for u in fixture['users'] if u['role']==role and u['box_ids']==['BOX-0001']) for role in roles]
  token=http('/api/v1/auth/login',{'username':users[0]['username'],'password':users[0]['password']})['data']['access_token']
  latest=lambda:http('/api/v1/boxes/BOX-0001/latest',token=token)['data']
  node('POSE');node('ENV','env-live-cycle')
  wait(lambda:latest()['alarm']['active'] is True,'sample ENV enters warning')
  atomic_json(directory/'environment-active.json',latest())
  wait(lambda:node_acked('ENV') and latest()['alarm']['active'] is False,'sample ENV recovery saved and precisely acknowledged',35)
  barrier=directory/'door-start.json'
  node('ENV','door-live-env',barrier);node('POSE','door-live-pose',barrier)
  atomic_json(barrier,{'start_at':int(time.time()*1000)+1500})
  wait(lambda:latest()['door']['state']=='OPEN' and latest()['door']['confidence']=='HIGH','real dual-source fusion HIGH',25)
  wait(lambda:node_acked('ENV') and node_acked('POSE'),'dual-source event batches precisely acknowledged')
  atomic_json(directory/'door-result.json',latest())
  vue=(FRONTEND/'node_modules/@vitejs/plugin-vue/dist/index.mjs').as_uri();vite_config=FRONTEND/'artifacts'/('rules-'+uuid.uuid4().hex+'.mjs');vite_config.parent.mkdir(exist_ok=True)
  vite_config.write_text('import vue from '+json.dumps(vue)+';export default {plugins:[vue()],server:{proxy:{"/api":{target:"http://127.0.0.1:18080",changeOrigin:false},"/ws":{target:"http://127.0.0.1:18080",ws:true,changeOrigin:false}}}};',encoding='utf-8')
  launch('frontend',node_command('dev')+['--config',str(vite_config),'--port','15173','--host','127.0.0.1','--strictPort'],frontend_environment(),FRONTEND);wait(lambda:port_open(15173),'isolated frontend ready',20)
  browser_input=directory/'browser-input.local.json';atomic_json(browser_input,{'url':'http://127.0.0.1:15173','directory':str(directory),'users':users})
  try:
   browser=launch('browser',[__import__('shutil').which('node'),str(FRONTEND/'tests/rules-live.cjs'),str(browser_input)],frontend_environment(),FRONTEND)
   wait(lambda:(directory/'browser-ready.json').is_file(),'browser subscribed before alarm')
   node('POSE','geofence-exit-enter')
   wait(lambda:latest()['alarm']['active'] is True,'sample geofence exit WARNING')
   wait(lambda:node_acked('POSE') and latest()['alarm']['active'] is False,'sample geofence reentry recovers')
   if browser.wait(timeout=70):raise AssertionError('Real browser rule check failed')
   checks.append({'check':'four-role real browser plus alarm start/recovery WS','passed':True})
  finally:browser_input.unlink(missing_ok=True)
  history=http('/api/v1/boxes/BOX-0001/events?size=100',token=token)['data'];atomic_json(directory/'history.json',history)
  kinds={x['type'] for x in history['items']}
  if not {'DOOR_CONCLUSION','ALARM','ENV_HIGH','ENV_RECOVERED','GEOFENCE_EXIT','GEOFENCE_ENTER'}.issubset(kinds):raise AssertionError('History chain missing')
  checks.append({'check':'raw and derived history traceable','passed':True});success=True
 finally:
  for process,stop,_ in nodes.values():stop.touch()
  time.sleep(.3)
  for child in reversed(children):stop_owned(child)
  if broker:broker.stop()
  for log in logs:log.close()
  if vite_config:vite_config.unlink(missing_ok=True)
  cleanup=all(p.poll() is not None for p in children) and not any(port_open(p) for p in ports)
  atomic_json(directory/'summary.json',{'passed':success and cleanup,'checks':checks,'children_stopped':cleanup,'database':'smartbox_test','database_reset':True,'broker':'cloud' if cloud else 'local'})
  if not cleanup:raise AssertionError('Own process cleanup failed')

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['software','cloud-smoke']);p.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json');p.add_argument('--run-dir',required=True,type=Path);p.add_argument('--allow-test-reset',action='store_true');a=p.parse_args()
 try:
  if not a.allow_test_reset:raise ValueError('Explicit project test reset consent required')
  execute(a.config,a.run_dir,a.action=='cloud-smoke');return 0
 except (ValueError,KeyError,OSError,AssertionError,subprocess.SubprocessError) as error:print('TASK-008 failed: '+type(error).__name__+'; inspect private evidence; credentials suppressed.');return 1
if __name__=='__main__':raise SystemExit(main())
