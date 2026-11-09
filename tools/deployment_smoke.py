"""Real development supervisor smoke check; called inside an isolated cold rehearsal."""
import json,os,subprocess,sys,time,urllib.request,uuid
from pathlib import Path
from project import ROOT

def check(directory):
    from simulator.broker import port_open
    from frontend import stop_owned
    if any(port_open(p) for p in (1883,8080,5173)):raise ValueError('Smoke ports occupied')
    directory.mkdir(parents=True,exist_ok=False)
    log=(directory/'supervisor.log').open('wb');process=None;result={'checks':[]}
    py=sys.executable;opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path,data=None,token=None,key=None):
        headers={}
        if token:headers['Authorization']='Bearer '+token
        if key:headers['Idempotency-Key']=key
        if data is not None:headers['Content-Type']='application/json'
        req=urllib.request.Request('http://127.0.0.1:8080'+path,data=json.dumps(data).encode() if data is not None else None,headers=headers)
        with opener.open(req,timeout=8) as response:return json.load(response)
    def wait(predicate,label,seconds=90):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            if process.poll() is not None:raise ValueError('Supervisor exited')
            try:
                if predicate():result['checks'].append(label);return
            except (OSError,ValueError,KeyError):pass
            time.sleep(.25)
        raise ValueError('Supervisor smoke timeout: '+label)
    try:
        subprocess.run([py,str(ROOT/'tools/mqtt.py'),'prepare'],cwd=ROOT,check=True,stdout=log,stderr=subprocess.STDOUT)
        process=subprocess.Popen([py,str(ROOT/'tools/project.py'),'start','--mode','simulator'],cwd=ROOT.parent,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        wait(lambda:request('/actuator/health')['status']=='UP','HTTP healthy')
        config=json.loads((ROOT/'secrets/backend-dev.bootstrap.local.json').read_text(encoding='utf-8'))
        user=next(u for u in config['users'] if u['role']=='LOGISTICS_OPERATOR')
        token=request('/api/v1/auth/login',{'username':user['username'],'password':user['password']})['data']['access_token']
        wait(lambda:request('/api/v1/boxes/BOX-0001/latest',token=token)['data']['online_mask']==3,'dual independent simulator nodes')
        record=json.loads((ROOT/'artifacts/development-running.json').read_text(encoding='utf-8'));run=Path(record['directory'])
        def acknowledged():
            for role in ('ENV','POSE'):
                state=json.loads((run/role/(role+'.status.json')).read_text(encoding='utf-8'))
                if not state['ready'] or state['event_seq']<1 or state['pending_events']!=0:return False
            return True
        wait(acknowledged,'database business acknowledgments clear exact pending events')
        body={'cmd':'output','params':{'kind':'light','on':True,'duration_s':2}}
        operation=request('/api/v1/boxes/BOX-0001/commands',body,token,'deployment-'+uuid.uuid4().hex)['data']['operation_id']
        wait(lambda:request('/api/v1/boxes/BOX-0001/commands/'+operation,token=token)['data']['status']=='SUCCEEDED','real REST Broker simulator receipt control',20)
        wait(lambda:port_open(5173),'frontend listening',15)
        subprocess.run([py,str(ROOT/'tools/project.py'),'status'],cwd=ROOT.parent,check=True,stdout=log,stderr=subprocess.STDOUT)
        result['passed']=True
    finally:
        if process and process.poll() is None:
            stopped=subprocess.run([py,str(ROOT/'tools/project.py'),'stop'],cwd=ROOT.parent,stdout=log,stderr=subprocess.STDOUT,timeout=40)
            if stopped.returncode:stop_owned(process)
            else:process.wait(timeout=10)
        log.close();result['ports_released']=not any(port_open(p) for p in (1883,8080,5173))
        (directory/'summary.json').write_text(json.dumps(result,indent=2))
    if not result['ports_released']:raise ValueError('Supervisor ports remain occupied')

if __name__=='__main__':
    check(ROOT/'artifacts/supervisor-smoke'/str(time.time_ns()))
