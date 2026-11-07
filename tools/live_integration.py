"""TASK-005 real Broker/MySQL/HTTP/WebSocket acceptance. No credentials in evidence."""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import time
import urllib.request
import urllib.error
from jsonschema import Draft202012Validator
from backend import local_environment,java_executable,JAR
from simulator.config import ROOT,CREATE_FLAGS,prepare
from simulator.broker import Broker,port_open
from simulator.scenarios import Run
from simulator.storage import atomic_json
from effective_contracts import check as check_effective

class WebSocket:
    """Small RFC6455 test client; tokens remain in process memory."""
    def __init__(self,ticket,origin='http://localhost:5173'):
        self.sock=socket.create_connection(('127.0.0.1',8080),timeout=4)
        key=base64.b64encode(os.urandom(16)).decode()
        request=('GET /ws?ticket='+ticket+' HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: '+key+'\r\nSec-WebSocket-Version: 13\r\nOrigin: '+origin+'\r\n\r\n')
        self.sock.sendall(request.encode());header=b''
        while not header.endswith(b'\r\n\r\n'):
            block=self.sock.recv(1)
            if not block:break
            header+=block
        self.status=int(header.split(b' ')[1]);self.closed=self.status!=101
        if self.status==101:
            expected=base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
            if expected not in header:raise AssertionError('WebSocket accept mismatch')
    def send_frame(self,opcode,payload):
        mask=os.urandom(4);length=len(payload)
        head=bytes([0x80|opcode,0x80|length]) if length<126 else bytes([0x80|opcode,0x80|126])+struct.pack('!H',length)
        self.sock.sendall(head+mask+bytes(v^mask[i%4] for i,v in enumerate(payload)))
    def send(self,value):self.send_frame(1,json.dumps(value).encode())
    def exact(self,n):
        out=b''
        while len(out)<n:
            block=self.sock.recv(n-len(out))
            if not block:raise ConnectionError('WebSocket closed')
            out+=block
        return out
    def receive(self,timeout=4):
        self.sock.settimeout(timeout)
        while True:
            a,b=self.exact(2);length=b&127
            if length==126:length=struct.unpack('!H',self.exact(2))[0]
            elif length==127:length=struct.unpack('!Q',self.exact(8))[0]
            payload=self.exact(length)
            if a&15==9:self.send_frame(10,payload);continue
            if a&15==8:self.closed=True;return None
            if a&15==1:return json.loads(payload)
    def close(self):
        try:
            if not self.closed:self.send_frame(8,b'')
        except OSError:pass
        self.sock.close();self.closed=True

class LiveRun:
    def __init__(self,directory,config):
        self.dir=directory;self.env=local_environment(config,'test');self.process=None;self.log=None;self.ws=[];self.results=[]
        # C1-only compilation reduces test JVM cold-start cost; wall-clock protocol deadlines stay unchanged.
        self.env['JAVA_TOOL_OPTIONS']=self.env.get('JAVA_TOOL_OPTIONS','')+' -XX:TieredStopAtLevel=1'
        self.fixture=json.loads(Path(self.env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'))
        self.common=json.loads((ROOT/'contracts/effective/common.schema.json').read_text(encoding='utf-8'))
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.run=None
    def check(self,condition,label):
        if not condition:raise AssertionError(label)
        self.results.append({'check':label,'passed':True});print('PASS: '+label,flush=True)
    def validate(self,data,definition):
        Draft202012Validator({'$schema':self.common['$schema'],'$defs':self.common['$defs'],'$ref':'#/$defs/'+definition}).validate(data)
    def request(self,path,method='GET',data=None,token=None,expected=200,key=None,definition=None):
        headers={}
        if token:headers['Authorization']='Bearer '+token
        if key:headers['Idempotency-Key']=key
        if data is not None:headers['Content-Type']='application/json'
        request=urllib.request.Request('http://127.0.0.1:8080'+path,data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
        try:
            with self.opener.open(request,timeout=10) as response:status=response.status;raw=response.read()
        except urllib.error.HTTPError as error:status=error.code;raw=error.read()
        if status!=expected:raise AssertionError('HTTP status '+str(status)+' for '+path)
        if path.endswith('/export'):return raw.decode()
        result=json.loads(raw)
        if definition:self.validate(result,definition)
        return result
    def login(self,role,box=1):
        user=next(u for u in self.fixture['users'] if u['role']==role and u['box_ids']==['BOX-000'+str(box)])
        return self.request('/api/v1/auth/login','POST',{'username':user['username'],'password':user['password']},definition='LoginResponse')['data']['access_token']
    def start(self):
        if port_open(8080):raise ValueError('Port 8080 occupied; no process stopped')
        self.log=(self.dir/('server-'+str(time.time_ns())+'.log')).open('wb')
        self.process=subprocess.Popen([java_executable(self.env),'-jar',str(JAR)],cwd=ROOT/'backend',env=self.env,stdout=self.log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS)
        end=time.monotonic()+35
        while time.monotonic()<end:
            if self.process.poll() is not None:raise AssertionError('Own backend exited')
            try:self.request('/actuator/health');return
            except (OSError,urllib.error.URLError):time.sleep(.2)
        raise AssertionError('Own backend startup timeout')
    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(10)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait(5)
        if self.log:self.log.close()
        self.process=None;self.log=None
    def wait(self,predicate,label,seconds=12):
        return self.run.wait(predicate,label,seconds)
    def ws_open(self,token,origin='http://localhost:5173'):
        ticket=self.request('/api/v1/ws-ticket','POST',token=token,definition='TicketResponse')['data']['ticket']
        ws=WebSocket(ticket,origin);self.ws.append(ws);return ws,ticket
    def execute(self):
        if check_effective():raise AssertionError('Effective contract drift')
        cfg=self.dir/'mqtt.local.json';config=prepare(cfg)
        self.run=Run(self.dir,cfg);self.run.broker=Broker(config,self.dir/'broker');self.run.broker.start()
        self.run.observer_start()
        self.env.update({'SMARTBOX_MQTT_ENABLED':'true','SMARTBOX_MQTT_USER':config['accounts']['backend']['username'],'SMARTBOX_MQTT_PASSWORD':config['accounts']['backend']['password']})
        drop=self.dir/'drop-ack';pause=self.dir/'pause-dispatch';drop.touch()
        self.env.update({'SMARTBOX_TEST_FAULTS':'true','SMARTBOX_TEST_DROP_ACK_FILE':str(drop),'SMARTBOX_TEST_PAUSE_DISPATCH_FILE':str(pause)})
        self.start();self.run.start_node('ENV');self.run.start_node('POSE')
        operator=self.login('LOGISTICS_OPERATOR');viewer=self.login('LOGISTICS_VIEWER');delivery=self.login('DELIVERY_USER');courier=self.login('COURIER')
        latest=lambda:self.request('/api/v1/boxes/BOX-0001/latest',token=operator,definition='LatestResponse')['data']
        self.wait(lambda:latest()['online_mask']==3,'dual nodes in backend')
        self.check(latest()['alarm']=={'assessment':'UNKNOWN','active':None,'level':None},'unknown alarm never claims normal')
        self.check(latest()['door']['state']=='UNKNOWN','unimplemented fusion stays unknown')
        self.request('/api/v1/boxes',token=operator,definition='BoxesResponse')
        limited=self.request('/api/v1/boxes/BOX-0001/latest',token=delivery,definition='LatestResponse')['data']
        self.check('pose' not in limited and 'sentry' not in limited,'delivery snapshot removes restricted fields')
        self.request('/api/v1/boxes/BOX-0002/latest',token=operator,expected=403)
        self.request('/api/v1/boxes/BOX-0001/nodes',token=delivery,expected=403)
        self.check(True,'real HTTP cross-box and diagnostic authorization')
        events=lambda:self.request('/api/v1/boxes/BOX-0001/events',token=operator,definition='EventsResponse')['data']
        self.wait(lambda:events()['total']==2,'committed events while ack suppressed')
        self.check(all(self.run.status(role)['pending_events']==1 for role in ('ENV','POSE')),'committed data without business ack remains pending at nodes')
        self.check(not self.run.messages('ENV','event_ack') and not self.run.messages('POSE','event_ack'),'test barrier suppresses ack after actual commit')
        drop.unlink()
        for role in ('ENV','POSE'):
            self.wait(lambda role=role:any(m['data'].get('result')=='stored' for m in self.run.messages(role,'event_ack')),'backend precise event acknowledgement')
            self.wait(lambda role=role:self.run.status(role)['pending_events']==0,'node queue cleared by backend')
        events=lambda:self.request('/api/v1/boxes/BOX-0001/events',token=operator,definition='EventsResponse')['data']
        self.wait(lambda:events()['total']>=2,'durable event projection')
        self.check(self.request('/api/v1/boxes/BOX-0001/events',token=delivery,definition='EventsResponse')['data']['total']==0,'delivery cannot read raw simulator events')
        original=copy.deepcopy(self.run.messages('ENV','event')[0]['data']);original['events'][0]['replayed']=True
        self.run.stop_node('ENV')
        publisher=self.run.peer('ENV');before_ack=len(self.run.messages('ENV','event_ack'))
        publisher.send(self.run.topic('ENV','event'),original)
        # This separate peer replaces ENV by its own account; stop the original before later restart.
        self.wait(lambda:len(self.run.messages('ENV','event_ack'))>before_ack,'consistent replay acknowledged')
        self.check(events()['total']==2,'replayed content deduplicates without new projection')
        acknowledged=len(self.run.messages('ENV','event_ack'))
        conflicting=copy.deepcopy(original);new_event=copy.deepcopy(conflicting['events'][0]);new_event['event_seq']=2
        conflicting['events'][0]['data']['code']='CONFLICT';conflicting['events'].insert(0,new_event)
        publisher.send(self.run.topic('ENV','event'),conflicting);self.run.pump(1)
        self.check(len(self.run.messages('ENV','event_ack'))==acknowledged and events()['total']==2,'mixed conflicting batch rolls back and receives no business ack')
        unsupported=copy.deepcopy(original);unsupported['events'][0].update({'event_seq':999,'type':'IMPACT','src':'POSE','data':{'peak_g':5,'duration_ms':10,'quality':'VALID'}})
        publisher.send(self.run.topic('ENV','event'),unsupported);self.run.pump(.5)
        self.check(len(self.run.messages('ENV','event_ack'))==acknowledged and events()['total']==2,'registered ENV capability rejects pose event over real broker')
        for p in list(self.run.peers):
            if p is not self.run.observer and p is not self.run.control:self.run.remove_peer(p)
        self.run.start_node('ENV',events=0)
        self.run.pump(1)
        self.request('/api/v1/boxes/BOX-0001/track',token=operator,definition='TrackResponse')
        self.check('TEST_INPUT' in self.request('/api/v1/boxes/BOX-0001/events/export',token=operator),'real CSV exports persisted events')
        self.request('/api/v1/boxes/BOX-0001/events/export',token=viewer,expected=403)
        self.request('/api/v1/boxes/BOX-0001/events?size=101',token=operator,expected=400)
        self.check(True,'CSV role and query bounds enforced')
        commands=[('output',{'kind':'light','on':True,'duration_s':2}),('gps_mode',{'mode':'ROUTE','seed':7}),('profile',{'profile_id':'LIVE-P1','temp_min_c':0,'temp_max_c':40,'humi_min_pct':20,'humi_max_pct':80,'temp_hysteresis_c':1,'humi_hysteresis_pct':2}),('calibrate',{}),('sentry',{'on':True,'duration_s':4,'sentry_revision':1})]
        operations=[]
        for i,(kind,params) in enumerate(commands):
            body={'cmd':kind,'params':params};key='live-'+str(i)
            op=self.request('/api/v1/boxes/BOX-0001/commands','POST',body,operator,202,key,'CommandResponse')['data']['operation_id'];operations.append(op)
            again=self.request('/api/v1/boxes/BOX-0001/commands','POST',body,operator,202,key)['data']['operation_id'];self.check(op==again,'persistent idempotency '+kind)
            operation=lambda op=op:self.request('/api/v1/boxes/BOX-0001/commands/'+op,token=operator,definition='OperationResponse')['data']
            self.wait(lambda:operation()['status']=='SUCCEEDED','command completed '+kind,15)
            self.check(all(n['status']=='SUCCEEDED' for n in operation()['nodes']),'real command execution and saved receipt '+kind)
        changed={'cmd':'output','params':{'kind':'light','on':False,'duration_s':2}}
        self.request('/api/v1/boxes/BOX-0001/commands','POST',changed,operator,409,'live-0')
        self.request('/api/v1/boxes/BOX-0001/commands','POST',changed,courier,403,'courier-no-output')
        self.check(True,'idempotency conflict and courier control restrictions')
        from concurrent.futures import ThreadPoolExecutor
        same={'cmd':'output','params':{'kind':'light','on':True,'duration_s':2}}
        with ThreadPoolExecutor(max_workers=4) as pool:
            ids=list(pool.map(lambda _:self.request('/api/v1/boxes/BOX-0001/commands','POST',same,operator,202,'concurrent')['data']['operation_id'],range(4)))
        self.check(len(set(ids))==1,'concurrent HTTP idempotency produces one operation')
        pause.touch()
        recover=self.request('/api/v1/boxes/BOX-0001/commands','POST',same,operator,202,'recover-publish')['data']['operation_id']
        saved_request=self.request('/api/v1/boxes/BOX-0001/commands/'+recover,token=operator)['data']['nodes'][0]['request_id']
        saved_at=time.monotonic()
        self.run.pump(.3)
        self.check(not any(m['data'].get('operation_id')==recover for m in self.run.messages('ENV','cmd')),'committed command not published before dispatch barrier')
        self.stop();pause.unlink();self.start();operator=self.login('LOGISTICS_OPERATOR')
        recovered=lambda:self.request('/api/v1/boxes/BOX-0001/commands/'+recover,token=operator)['data']['status']
        self.wait(lambda:recovered() in ('SUCCEEDED','EXPIRED'),'recover committed unpublished command',15)
        delivered=[m['data'] for m in self.run.messages('ENV','cmd') if m['data'].get('operation_id')==recover]
        if recovered()=='SUCCEEDED':
            self.check(bool(delivered) and all(m['request_id']==saved_request and m['expires_at']-m['issued_at']==15000 for m in delivered),'backend restart dispatches same persisted request within original lifetime')
        else:
            self.check(time.monotonic()-saved_at>=15 and not delivered,'expired persisted request is not reissued after slow restart')
        pause.touch()
        expired=self.request('/api/v1/boxes/BOX-0001/commands','POST',same,operator,202,'expire-before-publish')['data']['operation_id']
        self.run.pump(15.2);pause.unlink();self.run.pump(.6)
        self.check(self.request('/api/v1/boxes/BOX-0001/commands/'+expired,token=operator)['data']['status']=='EXPIRED' and not any(m['data'].get('operation_id')==expired for m in self.run.messages('ENV','cmd')),'expired unpublished command never executes or receives a new lifetime')
        ws,ticket=self.ws_open(operator);self.check(ws.status==101,'real WebSocket ticket handshake')
        reused=WebSocket(ticket);self.ws.append(reused);self.check(reused.status==401,'WebSocket ticket cannot be reused')
        ws.send({'v':1,'type':'subscribe','box_ids':['BOX-0001','BOX-0002']});self.check(ws.receive()['type']=='error','mixed unauthorized subscription rejected atomically')
        ws.send({'v':1,'type':'subscribe','box_ids':['BOX-0001']})
        message=ws.receive();self.validate(message,'WsPush');self.check(message['type']=='snapshot','authorized real WebSocket snapshot')
        pushed=self.request('/api/v1/boxes/BOX-0001/commands','POST',same,operator,202,'ws-notify')['data']['operation_id']
        deadline=time.monotonic()+10;found=False
        while time.monotonic()<deadline:
            message=ws.receive(4)
            if message is None:break
            self.validate(message,'WsPush')
            if message['type']=='command_status' and message['data']['operation_id']==pushed:found=True;break
        self.check(found,'real command outcome notification follows persisted operation')
        bad,_=self.ws_open(operator,'http://untrusted.invalid');self.check(bad.status==403,'WebSocket Origin rejected')
        dw,_=self.ws_open(delivery);dw.send({'v':1,'type':'subscribe','box_ids':['BOX-0001']});dm=dw.receive();self.validate(dm,'WsPush');self.check('pose' not in dm['data'] and 'sentry' not in dm['data'],'WebSocket delivery projection matches REST')
        # Restart the real backend: state/operations must survive, MQTT clean-session subscriptions return.
        before=events()['total'];self.stop();self.start();operator=self.login('LOGISTICS_OPERATOR')
        self.wait(lambda:latest()['online_mask']==3,'backend reconnect after restart')
        self.check(events()['total']==before,'database history survives backend restart')
        self.check(self.request('/api/v1/boxes/BOX-0001/commands/'+operations[0],token=operator)['data']['status']=='SUCCEEDED','operation survives backend restart')
        # A real silence window, without stopping the shared database or fabricating clock passage.
        self.run.stop_node('POSE');self.wait(lambda:latest()['online_mask']==1,'LWT/offline update')
        self.check(latest()['connectivity']=='PARTIAL','partial box remains queryable')
        self.run.start_node('POSE');self.wait(lambda:latest()['online_mask']==3,'node recovery')
        self.check(True,'new boot restores live aggregation')
        received=latest()['env']['received_at']
        self.run.broker.stop();self.run.pump(1);self.run.broker.start()
        for peer in list(self.run.peers):self.run.remove_peer(peer)
        self.run.observer_start()
        self.wait(lambda:latest()['env']['received_at']>received and latest()['online_mask']==3,'broker restart subscriptions recovered',25)
        self.check(True,'backend and nodes reconnect after actual broker restart')
        # Suspend only our own child: socket remains open, no synthetic offline/LWT is published.
        child=self.run.nodes['POSE']['process'];started=time.monotonic()
        if os.name=='nt':
            import ctypes
            native=ctypes.WinDLL('ntdll');suspend=native.NtSuspendProcess;resume=native.NtResumeProcess
            suspend.argtypes=resume.argtypes=[ctypes.c_void_p];suspend.restype=resume.restype=ctypes.c_long
            if suspend(int(child._handle))!=0:raise AssertionError('Own process suspension failed')
            restore=lambda:resume(int(child._handle))
        else:
            import signal
            child.send_signal(signal.SIGSTOP);restore=lambda:child.send_signal(signal.SIGCONT)
        try:
            while time.monotonic()-started<93:self.run.pump(1)
            elapsed=time.monotonic()-started
            self.check(elapsed>=90 and latest()['online_mask']==1,'real 90-second silence marks node offline')
            self.results[-1]['observed_silence_s']=round(elapsed,3)
        finally:restore()
        self.wait(lambda:latest()['online_mask']==3,'suspended node resumed',25)
        self.check(True,'online state recovers after real silent interval')

    def cleanup(self):
        for ws in self.ws:
            try:ws.close()
            except OSError:pass
        self.stop()
        if self.run:
            self.run.safe_nodes_stopped()
            for peer in self.run.peers:peer.close()
            if self.run.broker:self.run.broker.stop()
        self.check(not port_open() and not port_open(8080),'own processes cleaned and ports released')

    def dispatch_recovery(self):
        cfg=self.dir/'mqtt.local.json';config=prepare(cfg);self.run=Run(self.dir,cfg)
        self.run.broker=Broker(config,self.dir/'broker');self.run.broker.start();self.run.observer_start()
        pause=self.dir/'pause-dispatch';pause.touch()
        self.env.update({'SMARTBOX_MQTT_ENABLED':'true','SMARTBOX_MQTT_USER':config['accounts']['backend']['username'],'SMARTBOX_MQTT_PASSWORD':config['accounts']['backend']['password'],
                         'SMARTBOX_TEST_FAULTS':'true','SMARTBOX_TEST_PAUSE_DISPATCH_FILE':str(pause)})
        self.start();self.run.start_node('ENV',events=0);self.run.start_node('POSE',events=0);token=self.login('LOGISTICS_OPERATOR')
        latest=lambda:self.request('/api/v1/boxes/BOX-0001/latest',token=token)['data']
        self.wait(lambda:latest()['online_mask']==3,'dual nodes before dispatch interruption')
        body={'cmd':'output','params':{'kind':'light','on':True,'duration_s':2}}
        operation=self.request('/api/v1/boxes/BOX-0001/commands','POST',body,token,202,'broker-dispatch')['data']['operation_id']
        saved=self.request('/api/v1/boxes/BOX-0001/commands/'+operation,token=token)['data']['nodes'][0]['request_id']
        self.run.broker.stop();self.run.pump(1)
        self.run.broker.start()
        for peer in list(self.run.peers):self.run.remove_peer(peer)
        self.run.observer_start();pause.unlink()
        self.wait(lambda:self.request('/api/v1/boxes/BOX-0001/commands/'+operation,token=token)['data']['status']=='SUCCEEDED','persisted dispatch recovers after short broker interruption',13)
        messages=[m['data'] for m in self.run.messages('ENV','cmd') if m['data'].get('operation_id')==operation]
        self.check(bool(messages) and all(m['request_id']==saved and m['expires_at']-m['issued_at']==15000 for m in messages),'persisted dispatch resumes within original lifetime and keeps exact request identity')
        self.wait(lambda:self.run.status('ENV')['actions']==1,'node action snapshot')
        self.check(self.run.status('ENV')['actions']==1,'broker recovery does not duplicate simulated action')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json');parser.add_argument('--run-dir',type=Path,required=True);parser.add_argument('--allow-test-reset',action='store_true');parser.add_argument('--scenario',choices=['all','dispatch-recovery'],default='all');args=parser.parse_args()
    if not args.allow_test_reset:raise ValueError('Explicit test reset consent required')
    directory=args.run_dir.resolve();directory.relative_to((ROOT/'artifacts').resolve())
    if directory.exists():raise ValueError('Use a new private evidence directory')
    directory.mkdir(parents=True)
    run=LiveRun(directory,args.config);passed=False;error=None
    try:
        env=run.env.copy();env['SMARTBOX_TEST_ALLOW_RESET']='true';env['SPRING_MAIN_WEB_APPLICATION_TYPE']='none';env['SMARTBOX_MQTT_ENABLED']='false'
        with (directory/'test-reset.log').open('wb') as output:
            result=subprocess.run([java_executable(env),'-jar',str(JAR),'--smartbox.integration.reset=true','--spring.main.web-application-type=none'],cwd=ROOT/'backend',env=env,stdout=output,stderr=subprocess.STDOUT,timeout=40,creationflags=CREATE_FLAGS)
        if result.returncode:raise AssertionError('Isolated test reset failed')
        if args.scenario=='all':run.execute()
        else:run.dispatch_recovery()
        passed=True
    except Exception as failure:
        error=type(failure).__name__+(': '+str(failure)[:180] if isinstance(failure,AssertionError) else '')
        print('FAIL: '+error,flush=True)
    finally:
        try:run.cleanup()
        except Exception as cleanup:passed=False;error='Cleanup: '+type(cleanup).__name__
        atomic_json(directory/'summary.json',{'passed':passed,'error':error,'checks':run.results,'count':len(run.results),'test_database':'smartbox_test'})
    return 0 if passed else 1

if __name__=='__main__':raise SystemExit(main())
