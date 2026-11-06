"""Real broker scenarios. Synthetic confirmations are never database evidence."""
from __future__ import annotations
import copy
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from collections import deque
import paho.mqtt.client as mqtt
from .config import ROOT, DEFAULT, CREATE_FLAGS, prepare, load, confined
from .broker import Broker, port_open
from .protocol import pack, unpack, Protocol, Rejected
from .storage import atomic_json

class Peer:
    def __init__(self,config,account='control',client_id=None,password=None,anonymous=False):
        self.messages=deque(maxlen=10000);self.conn=None;self.disconnected=False;self.subscribed=False
        a=config['accounts'][account]
        self.client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id=client_id or a['username'],clean_session=True,protocol=mqtt.MQTTv311,reconnect_on_failure=False)
        if not anonymous:self.client.username_pw_set(a['username'],a['password'] if password is None else password)
        self.client.on_connect=lambda c,u,f,r,p:setattr(self,'conn',not r.is_failure)
        self.client.on_disconnect=lambda c,u,f,r,p:setattr(self,'disconnected',True)
        self.client.on_subscribe=lambda c,u,m,r,p:setattr(self,'subscribed',not any(x.is_failure for x in r))
        self.client.on_message=self.receive
        self.client.connect_timeout=3
        self.client.connect('127.0.0.1',1883,60)
        deadline=time.monotonic()+5
        while self.conn is None and time.monotonic()<deadline:self.pump()
        if self.conn is None:raise AssertionError('peer handshake timeout')
    def receive(self,c,u,m):
        try:data=unpack(bytes(m.payload))
        except Rejected:data=None
        self.messages.append({'topic':m.topic,'data':data,'raw':bytes(m.payload),'at':time.monotonic(),'retain':m.retain,'qos':m.qos})
    def subscribe(self,topic):
        self.subscribed=False;self.client.subscribe(topic,1)
        deadline=time.monotonic()+5
        while not self.subscribed and time.monotonic()<deadline:self.pump()
        if not self.subscribed:raise AssertionError('observer subscription timeout')
    def pump(self):
        try:self.client.loop(timeout=.01)
        except OSError:pass
    def send(self,topic,data,qos=1,retained=False):
        raw=data if isinstance(data,bytes) else pack(data)
        result=self.client.publish(topic,raw,qos,retained)
        if result.rc!=mqtt.MQTT_ERR_SUCCESS:raise AssertionError('test peer not connected')
    def close(self):
        try:self.client.disconnect();self.client.loop(timeout=.02)
        except OSError:pass

class Run:
    def __init__(self,directory,config_path):
        self.dir=directory;self.config_path=config_path;self.config=load(config_path)
        self.nodes={};self.records=[];self.peers=[];self.results=[];self.started=time.monotonic();self.request_seq=0
        self.observer=None;self.control=None;self.broker=None;self.protocol=Protocol();self.next_progress=0;self.cleanup_errors=[]
    def pump(self,duration=.05):
        deadline=time.monotonic()+duration
        while time.monotonic()<deadline:
            for peer in list(self.peers):peer.pump()
            time.sleep(.005)
            now=time.monotonic()
            if now>=self.next_progress:
                print('MQTT integration running: '+str(round(now-self.started,1))+'s; completed checks='+str(len(self.results)),flush=True)
                self.next_progress=now+20
    def wait(self,predicate,label,timeout=8):
        deadline=time.monotonic()+timeout
        while True:
            value=predicate()
            if value:return value
            if time.monotonic()>deadline:raise AssertionError(label)
            self.pump(.03)
    def check(self,condition,case,label):
        if not condition:raise AssertionError(case+': '+label)
        self.results.append({'case':case,'check':label,'passed':True,'elapsed_s':round(time.monotonic()-self.started,3)})
    def peer(self,*args,**kwargs):
        p=Peer(self.config,*args,**kwargs);self.peers.append(p);return p
    def remove_peer(self,p):
        if p is not None:
            p.close()
            if p in self.peers:self.peers.remove(p)
    def observer_start(self):
        self.observer=self.peer('observer');self.observer.subscribe('box/#')
        self.control=self.peer('control')
    def topic(self,role,suffix):return 'box/BOX-0001/BOX-0001-'+role+'/'+suffix
    def start_node(self,role,scenario='normal',events=1):
        counter=len(self.records);folder=self.dir/('node-'+role+'-'+str(counter));folder.mkdir()
        stop=folder/'stop';log=(folder/'node.log').open('wb')
        command=[sys._base_executable if os.name=='nt' else sys.executable,str(ROOT/'tools/mqtt.py'),'node','--config',str(self.config_path),'--role',role,
                 '--run-dir',str(folder),'--stop-file',str(stop),'--scenario',scenario,'--events',str(events)]
        env=os.environ.copy()
        if os.name=='nt':
            # Bypass the Windows venv redirector so Popen owns the actual worker PID.
            env['PYTHONPATH']=str(Path(sys.prefix)/'Lib/site-packages')+os.pathsep+env.get('PYTHONPATH','')
        process=subprocess.Popen(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=CREATE_FLAGS)
        record={'process':process,'folder':folder,'stop':stop,'log':log,'role':role}
        self.records.append(record);self.nodes[role]=record
        self.wait(lambda:self.status(role).get('ready'),'node '+role+' readiness',12)
        return record
    def status(self,role):
        r=self.nodes[role]
        if r['process'].poll() is not None:raise AssertionError('Own '+role+' node exited; inspect private node.log')
        path=r['folder']/(role+'.status.json')
        try:
            value=json.loads(path.read_text(encoding='utf-8'))
            return value if value.get('pid')==r['process'].pid else {}
        except (OSError,ValueError):return {}
    def stop_node(self,role,graceful=True):
        r=self.nodes.pop(role)
        if r['process'].poll() is None:
            if graceful:r['stop'].touch()
            else:r['process'].terminate()
            deadline=time.monotonic()+6
            while r['process'].poll() is None and time.monotonic()<deadline:self.pump(.03)
            if r['process'].poll() is None:r['process'].kill();r['process'].wait(timeout=3)
        r['log'].close()
        if graceful and r['process'].returncode!=0:raise AssertionError('Own node failed during graceful stop')
    def messages(self,role,suffix):
        return [m for m in self.observer.messages if m['topic']==self.topic(role,suffix) and m['data'] is not None]
    def command(self,role,kind='output',params=None,caps=None):
        self.request_seq+=1;now=int(time.time()*1000)
        caps=caps if caps is not None else {'output':2,'sentry':64,'profile':1,'gps_mode':32,'calibrate':8}[kind]
        return {'v':1,'box_id':'BOX-0001','node_id':'BOX-0001-'+role,'request_id':'sim-r'+str(self.request_seq),
                'operation_id':'sim-o'+str(self.request_seq),'cmd':kind,'target_caps':caps,
                'issued_at':now,'expires_at':now+15000,
                'params':params if params is not None else {'kind':'light','on':True,'duration_s':2}}
    def ack(self,role,command,timeout=8):
        self.control.send(self.topic(role,'cmd'),command)
        return self.wait(lambda:next((m for m in self.messages(role,'command_ack') if m['data']['request_id']==command['request_id']),None),'command acknowledgement',timeout)
    def safe_nodes_stopped(self):
        for role in list(self.nodes):
            try:self.stop_node(role)
            except BaseException as error:
                self.cleanup_errors.append(type(error).__name__)
                r=self.nodes.pop(role,None)
                if r is not None:
                    if r['process'].poll() is None:r['process'].kill();r['process'].wait(timeout=5)
                    r['log'].close()
        for r in self.records:
            if r['process'].poll() is None:r['process'].kill();r['process'].wait(timeout=5)
            r['log'].close()

    def execute(self):
        self.broker=Broker(self.config,self.dir/'broker');self.broker.start()
        for kwargs in ({'anonymous':True},{'password':'intentionally-wrong-test-password'}):
            p=self.peer(**kwargs);self.check(p.conn is False,'M02','authentication rejected');self.remove_peer(p)
        self.observer_start()
        self.start_node('ENV');self.start_node('POSE')
        self.wait(lambda:self.messages('ENV','property') and self.messages('POSE','property'),'dual properties')
        for role in ('ENV','POSE'):
            m=self.messages(role,'property')[-1]
            self.protocol.validate(m['topic'],m['raw'],0,caps=71 if role=='ENV' else 120)
            self.check(m['data']['caps']==(71 if role=='ENV' else 120),'M01',role+' identity and capabilities')
        boot=self.status('ENV')['boot_id'];connections=self.status('ENV')['connections']
        self.remove_peer(self.control)
        # Username-as-clientid must prevent a test account from evicting ENV.
        self.control=self.peer('control',client_id='BOX-0001-ENV');self.pump(.3)
        self.check(self.status('ENV')['boot_id']==boot and self.status('ENV')['connections']==connections,'M02','spoofed clientId did not evict node')
        # Publishing succeeds at MQTT level for some ACL denials; inspect actual delivery.
        marker={'probe':'unauthorized-upstream'}
        self.control.send(self.topic('ENV','property'),marker,0);self.pump(.4)
        self.check(not any(m['data']==marker for m in self.observer.messages),'M02','controller cannot publish node upstream')
        self.observer.send(self.topic('ENV','cmd'),{'probe':'readonly-write'});self.pump(.4)
        self.check(not any(m['data']=={'probe':'readonly-write'} for m in self.observer.messages),'M02','read-only observer cannot publish')

        backend=self.peer('backend');backend.subscribe(self.topic('ENV','heartbeat'))
        state={'v':1,'box_id':'BOX-0001','node_id':'BOX-0001-ENV','door_state':'OPEN',
               'conclusion_id':'sim-conclusion','historical':False,'ts':int(time.time()*1000)}
        backend.send(self.topic('ENV','box_state'),state)
        self.wait(lambda:self.status('ENV').get('box_states',0)==1,'backend box_state reception')
        self.check(True,'M03','backend account sends valid box_state without invented suppression')
        backend.send(self.topic('ENV','property'),{'probe':'backend-upstream'},0);self.pump(.3)
        self.check(not any(m['data']=={'probe':'backend-upstream'} for m in self.observer.messages),'M02','backend account cannot forge upstream')
        self.remove_peer(backend)
        c=self.command('ENV');before=self.status('ENV')['actions'];a=self.ack('ENV',c)
        self.check(a['data']['result']=='ok','M05','ENV output completed')
        reordered=copy.deepcopy(c);reordered['params']=dict(reversed(list(c['params'].items())))
        self.control.send(self.topic('ENV','cmd'),reordered);self.pump(.25)
        self.check(self.status('ENV')['actions']==before+1,'M08','duplicate request does not repeat action')
        conflict=copy.deepcopy(c);conflict['params']['on']=False
        self.control.send(self.topic('ENV','cmd'),conflict)
        self.wait(lambda:any(m['data']['request_id']==c['request_id'] and m['data']['code']=='conflicting_request' for m in self.messages('ENV','command_ack')),'conflicting request')
        self.check(True,'M08','same key different request rejected')
        self.wait(lambda:self.status('ENV').get('outputs',{}).get('light') is False,'output expiry',4)
        self.check(True,'M05','output automatically closed')
        p={'profile_id':'SIM-P1','temp_min_c':0,'temp_max_c':30,'temp_hysteresis_c':1,'humi_min_pct':0,'humi_max_pct':90,'humi_hysteresis_pct':1}
        self.check(self.ack('ENV',self.command('ENV','profile',p))['data']['actual_state']['profile_id']=='SIM-P1','M05','profile simulation')
        self.check(self.ack('POSE',self.command('POSE','gps_mode',{'mode':'WALK','seed':7}))['data']['actual_state']['mode']=='WALK','M05','GPS Mock mode')
        self.check(self.ack('POSE',self.command('POSE','calibrate',{}))['data']['actual_state']['calibrated'],'M05','simulated calibration')
        begin=time.monotonic();c=self.command('ENV','sentry',{'on':True,'duration_s':2,'sentry_revision':1})
        self.control.send(self.topic('ENV','cmd'),c);self.pump(1)
        self.control.send(self.topic('ENV','cmd'),c);self.pump(.2)
        self.check(not any(m['data']['request_id']==c['request_id'] for m in self.messages('ENV','command_ack')),'M08','duplicate during ARMING has no premature completion')
        a=self.ack('ENV',c)
        self.check(a['at']-begin>=2.9 and a['data']['actual_state']['sentry']=='ARMED','M05','ARMED acknowledgement follows 3s buffer')
        self.pump(1)
        self.check(self.status('ENV')['sentry']=='ARMED','M05','CR-003 protection duration excludes buffer')
        self.wait(lambda:self.status('ENV').get('sentry')=='DISARMED','sentry expiry',3)
        self.check(True,'M05','CR-003 automatic disarm after active duration')
        c=self.command('ENV','sentry',{'on':True,'duration_s':0,'sentry_revision':2})
        self.ack('ENV',c);self.pump(2.1)
        self.check(self.status('ENV')['sentry']=='ARMED','M05','zero duration has no automatic expiry')
        self.check(self.ack('ENV',self.command('ENV','sentry',{'on':False,'duration_s':0,'sentry_revision':3}))['data']['actual_state']['sentry']=='DISARMED','M05','explicit disarm')
        stale=self.ack('ENV',self.command('ENV','sentry',{'on':True,'duration_s':2,'sentry_revision':2}))
        self.check(stale['data']['code']=='stale_revision','M06','old revision rejected')
        for kind,params,caps,expected in [('calibrate',{},8,'unsupported_caps'),('output',{'kind':'indicator','on':True,'duration_s':1},16,'unsupported_caps')]:
            self.check(self.ack('ENV',self.command('ENV',kind,params,caps))['data']['code']==expected,'M06','unsupported capability rejected')
        expired=self.command('ENV');expired['issued_at']-=20000;expired['expires_at']-=20000
        self.check(self.ack('ENV',expired)['data']['code']=='expired','M06','expired command rejected')
        for change in ({'v':2},{'params':{'kind':'light','on':True,'duration_s':31}}):
            c=self.command('ENV');c.update(change)
            self.check(self.ack('ENV',c)['data']['result']=='error','M04','invalid version or parameter rejected')
        before=self.status('ENV')['dropped']
        for raw in (b'{',b'{"v":1,"v":2}',b'\xff',b'{"v":NaN}'):
            self.control.send(self.topic('ENV','cmd'),raw)
        self.wait(lambda:self.status('ENV')['dropped']>=before+4,'malformed payload rejection')
        self.check(True,'M04','malformed UTF8/JSON/duplicate/NaN rejected')
        c=self.command('ENV');c['extra']='x'*(1536-len(pack({**c,'extra':''})))
        self.check(len(pack(c))==1536 and self.ack('ENV',c)['data']['result']=='ok','M04','1536-byte payload accepted')
        c=self.command('ENV');c['extra']='x'*(1537-len(pack({**c,'extra':''})))
        before=self.status('ENV')['dropped'];self.control.send(self.topic('ENV','cmd'),c)
        self.wait(lambda:self.status('ENV')['dropped']>before,'oversized payload rejection')
        self.check(True,'M04','1537-byte payload rejected')
        pending=self.status('ENV')['pending_events']
        self.pump(.3);self.check(pending==1 and self.status('ENV')['pending_events']==1,'M09','PUBACK did not remove event')
        event=self.messages('ENV','event')[0]['data'];seq=event['events'][0]['event_seq']
        ack={'v':1,'box_id':'BOX-0001','node_id':'BOX-0001-ENV','boot_id':boot+99,'event_seqs':[seq],'result':'stored','ts':int(time.time()*1000)}
        self.control.send(self.topic('ENV','event_ack'),ack);self.pump(.3)
        self.check(self.status('ENV')['pending_events']==1,'M09','wrong boot confirmation ignored')
        ack['boot_id']=boot;ack['node_id']='BOX-0001-POSE';self.control.send(self.topic('ENV','event_ack'),ack);self.pump(.3)
        self.check(self.status('ENV')['pending_events']==1,'M09','wrong identity confirmation ignored')
        ack['node_id']='BOX-0001-ENV';self.control.send(self.topic('ENV','event_ack'),ack)
        self.wait(lambda:self.status('ENV')['pending_events']==0,'exact confirmation')
        self.control.send(self.topic('ENV','event_ack'),ack);self.pump(.2)
        self.check(self.status('ENV')['pending_events']==0,'M09','exact and duplicate confirmation are safe test inputs')
        # Keep both original processes alive for at least two full 30-second intervals.
        self.wait(lambda:len(self.messages('ENV','heartbeat'))>=3 and len(self.messages('POSE','heartbeat'))>=3,'two real heartbeat intervals',70)
        for role in ('ENV','POSE'):
            h=self.messages(role,'heartbeat')[:3];deltas=[h[i+1]['at']-h[i]['at'] for i in range(2)]
            self.check(all(29<=d<=33 for d in deltas),'M01',role+' two real 30s heartbeat intervals')
            atomic_json(self.dir/(role+'-heartbeat-timing.json'),{'intervals_s':deltas,'uptime_ms':[m['data']['uptime_ms'] for m in h]})

        # Role credentials cannot read the other node or publish into its hierarchy.
        self.stop_node('ENV');intruder=self.peer('ENV');intruder.subscribe(self.topic('POSE','property'))
        self.pump(1.2)
        self.check(not intruder.messages,'M02','ENV cannot subscribe POSE data')
        intruder.send(self.topic('POSE','property'),{'probe':'cross-node'},0)
        self.pump(.3);self.check(not any(m['data']=={'probe':'cross-node'} for m in self.observer.messages),'M02','cross-node publish blocked')
        intruder.send(self.topic('ENV','cmd'),{'probe':'node-downstream'})
        self.pump(.3);self.check(not any(m['data']=={'probe':'node-downstream'} for m in self.observer.messages),'M02','node cannot publish downstream')
        self.remove_peer(intruder)
        retained=self.command('ENV');self.control.send(self.topic('ENV','cmd'),retained,retained=True);self.pump(.2)
        self.start_node('ENV',events=0)
        self.wait(lambda:any(m['data']['request_id']==retained['request_id'] and m['data']['code']=='retained_command' for m in self.messages('ENV','command_ack')),'actual retained delivery refusal')
        self.check(self.status('ENV')['actions']==0,'M07','retained command delivered at subscribe and rejected')
        self.control.send(self.topic('ENV','cmd'),b'',retained=True);self.pump(.2)
        self.check(self.status('ENV')['boot_id']>boot,'M12','restart reserved a new boot generation')
        # New node with invalid quality and then no reliable UTC.
        self.stop_node('POSE');self.start_node('POSE','invalid',events=0)
        self.wait(lambda:any(m['data']['pose']['valid'] is False for m in self.messages('POSE','property')),'invalid mock quality')
        self.check(True,'M03','invalid sample retains null measurements')
        self.stop_node('POSE');self.start_node('POSE','unsynced',events=0)
        self.wait(lambda:any(m['data']['ts_src']=='UNSYNCED' for m in self.messages('POSE','property')),'unsynced sample')
        c=self.command('POSE','output',{'kind':'indicator','on':True,'duration_s':1},16)
        self.check(self.ack('POSE',c)['data']['code']=='time_unsynced','M06','UNSYNCED control rejected')
        self.stop_node('POSE');self.start_node('POSE',events=0)
        self.stop_node('ENV');self.start_node('ENV',events=70)
        status=self.status('ENV');self.check(status['pending_events']==64 and status['overflow_total']==6,'M10','64-item queue covers oldest and counts loss')
        generation=status['boot_id']
        self.wait(lambda:any(m['data']['boot_id']==generation and m['data']['overflow_total']==6 for m in self.messages('ENV','heartbeat')),'overflow heartbeat')
        first=self.wait(lambda:next((m for m in self.messages('ENV','event') if m['data']['boot_id']==generation),None),'event batch')
        self.wait(lambda:any(m['data']['boot_id']==generation and any(e['replayed'] for e in m['data']['events']) for m in self.messages('ENV','event')),'lost confirmation retransmission',8)
        replay=[m for m in self.messages('ENV','event') if m['data']['boot_id']==generation and any(e['replayed'] for e in m['data']['events'])][0]
        original={e['event_seq']:e for e in first['data']['events']}
        self.check(all(e['ts']==original[e['event_seq']]['ts'] and e['uptime_ms']==original[e['event_seq']]['uptime_ms'] for e in replay['data']['events'] if e['event_seq'] in original),'M10','retransmission preserves original event time')
        ack={'v':1,'box_id':'BOX-0001','node_id':'BOX-0001-ENV','boot_id':generation,'event_seqs':[7,9],'result':'stored','ts':int(time.time()*1000)}
        self.control.send(self.topic('ENV','event_ack'),ack)
        self.wait(lambda:self.status('ENV')['pending_events']==62,'partial exact ack')
        self.check(True,'M09','partial confirmation is not cumulative')
        # Full MQTT frame capacity is enforced by the actual broker too.
        before=self.status('ENV')['dropped']
        self.control.send(self.topic('ENV','cmd'),b'x'*(2048-7-len(self.topic('ENV','cmd').encode())))
        self.wait(lambda:self.status('ENV')['dropped']>before,'2048-byte frame reaches node')
        self.check(not self.control.disconnected,'M04','2048-byte frame reaches application payload rejection')
        self.control.send(self.topic('ENV','cmd'),b'x'*(2049-7-len(self.topic('ENV','cmd').encode())))
        self.wait(lambda:self.control.disconnected,'oversized MQTT frame disconnect')
        self.check(True,'M04','2049-byte MQTT frame rejected by Broker')
        self.remove_peer(self.control);self.control=self.peer('control')
        self.wait(lambda:self.status('ENV').get('ready'),'ENV still healthy')
        boot_before=self.status('ENV')['boot_id'];connections_before={role:self.status(role)['connections'] for role in ('ENV','POSE')}
        self.remove_peer(self.control);self.remove_peer(self.observer)
        self.broker.stop();self.pump(1.2);self.broker.start();self.observer_start()
        self.wait(lambda:all(self.status(role).get('ready') and self.status(role).get('connections',0)>connections_before[role] for role in ('ENV','POSE')),'both nodes reconnect and subscriptions',40)
        self.wait(lambda:self.messages('ENV','property') and self.messages('POSE','property'),'post-reconnect properties',8)
        self.check(self.status('ENV')['boot_id']==boot_before,'M11','Broker restart resubscribed without changing boot')
        self.check(self.ack('ENV',self.command('ENV'))['data']['result']=='ok','M11','downstream works after reconnect')
        # LWT must come from Broker after an abnormal child exit.
        pose_boot=self.status('POSE')['boot_id'];self.stop_node('POSE',graceful=False)
        self.wait(lambda:any(m['data']['boot_id']==pose_boot and m['data']['online'] is False for m in self.messages('POSE','availability')),'Broker LWT after crash',8)
        self.check(True,'M12','abnormal exit produced LWT')
        self.start_node('POSE',events=0)
        self.check(self.status('POSE')['boot_id']>pose_boot,'M12','OS lock released and boot advanced after crash')
        env_boot=self.status('ENV')['boot_id'];self.stop_node('ENV')
        self.wait(lambda:any(m['data']['boot_id']==env_boot and m['data']['online'] is False for m in self.messages('ENV','availability')),'normal offline publication')
        self.check(True,'M12','normal shutdown published offline')
        # Port occupied check must not disturb this project's running broker either.
        refused=False
        try:Broker(self.config,self.dir/'must-not-start').start()
        except ValueError:refused=True
        self.check(refused and self.broker.process.poll() is None,'M13','occupied port refused without stopping owner')

def integration(run_dir,config_path=None):
    run_dir=confined(run_dir,ROOT);run_dir.mkdir(parents=True,exist_ok=True)
    # Never consume/reset a daily development config in integration.
    expected=run_dir/'integration.local.json'
    if config_path is not None and confined(config_path,ROOT)!=expected:
        raise ValueError('Integration configuration must belong to its private run directory')
    if port_open():raise ValueError('Port occupied before integration; nothing stopped')
    prepare(expected)
    run=Run(run_dir,expected);failure=None
    try:run.execute()
    except BaseException as error:
        failure=error
    finally:
        run.safe_nodes_stopped()
        for peer in list(run.peers):run.remove_peer(peer)
        if run.broker:run.broker.stop()
        stopped=all(r['process'].poll() is not None for r in run.records) and not port_open()
        if run.cleanup_errors and failure is None:failure=RuntimeError('Node cleanup did not complete normally')
        summary={'date':datetime.now(timezone.utc).isoformat(),'passed':failure is None and stopped,
                 'checks':run.results,'checks_completed':len(run.results),'children_started':len(run.records),
                 'all_own_children_stopped':stopped,'cleanup_errors':run.cleanup_errors,'port_1883_released':not port_open(),
                 'synthetic_event_ack_only':True,'pending_storage':'VOLATILE/DEGRADED',
                 'failure':None if failure is None else type(failure).__name__+': '+str(failure)}
        atomic_json(run_dir/'summary.json',summary)
    if failure is not None:
        print('FAIL: '+str(failure),flush=True);return 1
    if not stopped:print('FAIL: own process cleanup incomplete',flush=True);return 1
    print('PASS: real MQTT checks='+str(len(run.results))+'; all owned processes stopped; no database claims.',flush=True)
    return 0
