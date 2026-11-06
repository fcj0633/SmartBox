"""Real loopback HTTP smoke checks; credentials/tokens stay in memory."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import urllib.error
from jsonschema import Draft202012Validator
from referencing import Registry,Resource
ROOT=Path(__file__).resolve().parents[1]
JAR=ROOT/'backend/target/smartbox-backend-0.1.0.jar'

def check(env,report_dir,java):
    report_dir=report_dir.resolve();report_dir.mkdir(parents=True,exist_ok=True)
    port=int(env.get('SERVER_PORT','8080'))
    if env.get('SERVER_ADDRESS')!='127.0.0.1':raise ValueError('Smoke server must be loopback-only')
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',port))==0:raise ValueError('HTTP port is occupied; no existing process was stopped')
    schema=json.loads((ROOT/'contracts/common/v1.schema.json').read_text(encoding='utf-8'))
    uri=schema['$id'];registry=Registry().with_resource(uri,Resource.from_contents(schema))
    def validate(value,name):
        errors=list(Draft202012Validator({'$ref':uri+'#/$defs/'+name},registry=registry).iter_errors(value))
        if errors:raise RuntimeError('Schema rejected '+name+' at '+str(list(errors[0].absolute_path))+' rule '+str(errors[0].validator))
    results=[];base='http://127.0.0.1:'+str(port)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path,method='GET',data=None,token=None,expected=200,definition=None,headers=None):
        h=dict(headers or {})
        if token:h['Authorization']='Bearer '+token
        if data is not None:h['Content-Type']='application/json'
        req=urllib.request.Request(base+path,data=json.dumps(data,ensure_ascii=False).encode() if data is not None else None,headers=h,method=method)
        try:
            with opener.open(req,timeout=10) as response:status=response.status;body=response.read()
        except urllib.error.HTTPError as error:status=error.code;body=error.read()
        if status!=expected:raise RuntimeError('Unexpected HTTP '+str(status)+' for '+method+' '+path)
        value=json.loads(body)
        if definition:validate(value,definition)
        results.append({'method':method,'path':path,'status':status,'schema':definition})
        return value
    log=report_dir/'http-server.log';process=None;summary={}
    try:
        with log.open('wb') as output:
            process=subprocess.Popen([java,'-jar',str(JAR)],cwd=ROOT/'backend',env=env,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
            deadline=time.monotonic()+45
            while True:
                if process.poll() is not None:raise RuntimeError('Backend exited before readiness; inspect private server log')
                try:
                    with opener.open(base+'/actuator/health',timeout=1) as response:
                        if json.loads(response.read())=={'status':'UP'}:break
                except (OSError,ValueError):pass
                if time.monotonic()>deadline:raise RuntimeError('Backend readiness timeout')
                time.sleep(.25)
            health=request('/actuator/health');assert health=={'status':'UP'}
            fixture=json.loads(Path(env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'))
            for user in fixture['users']:
                credentials={'username':user['username'],'password':user['password']}
                logged=request('/api/v1/auth/login','POST',credentials,definition='LoginResponse')
                token=logged['data']['access_token']
                assert logged['data']['role']==user['role']
                me=request('/api/v1/me',token=token,definition='MeResponse');assert set(me['data']['box_ids'])==set(user['box_ids'])
                own='/api/v1/boxes/'+user['box_ids'][0]+'/nodes'
                if user['role']=='DELIVERY_USER':request(own,token=token,expected=403,definition='Error')
                else:
                    nodes=request(own,token=token,definition='NodesResponse')['data'];assert len(nodes)==2
                    assert all(not n['online'] and n['boot_id'] is None and n['last_received_at'] is None for n in nodes)
                request('/api/v1/boxes/NO-ACCESS/nodes',token=token,expected=403,definition='Error')
            request('/api/v1/me',expected=401,definition='Error')
            request('/api/v1/me',token='malformed',expected=401,definition='Error')
            request('/api/v1/auth/login','POST',{},expected=400,definition='Error')
            request('/api/v1/auth/login','POST',{'username':123,'password':456},expected=400,definition='Error')
            unknown=request('/api/v1/no-such-endpoint',token=token,expected=404,definition='Error')
            # Successful preflight is transport setup, not a successful business authorization.
            req=urllib.request.Request(base+'/api/v1/me',headers={'Origin':'http://localhost:5173','Access-Control-Request-Method':'GET'},method='OPTIONS')
            with opener.open(req,timeout=5) as response:
                assert response.status==200 and response.headers.get('Access-Control-Allow-Origin')=='http://localhost:5173'
            summary={'profile':env['SPRING_PROFILES_ACTIVE'],'cases_checked':len(results)+1,'results':results,'jar_sha256':hashlib.sha256(JAR.read_bytes()).hexdigest(),'server_pid':process.pid}
    finally:
        if process is not None:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            with socket.socket() as probe:
                port_closed=probe.connect_ex(('127.0.0.1',port))!=0
            summary['server_stopped']=process.poll() is not None and port_closed
            if not summary['server_stopped']:raise RuntimeError('Own HTTP process did not fully release its port')
        (report_dir/'http-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('PASS: real HTTP and frozen Schema checks='+str(summary['cases_checked'])+'; own server stopped; credentials/tokens not recorded')
    return 0
