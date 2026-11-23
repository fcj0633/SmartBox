"""One process/one thread owns each MQTT client, including all its callbacks."""
from __future__ import annotations
from collections import deque
import json
from pathlib import Path
import random
import socket
import ssl
import time
import paho.mqtt.client as mqtt
from .config import DOWN
from .model import Model, Exhausted
from .protocol import Protocol, pack, unpack, Rejected
from .storage import IdentityState, FileLock, atomic_json

class Node:
    def __init__(self,config,role,run_dir,scenario='normal',events=1,seed=1,rule_case=None,rule_start_file=None):
        self.config=config;self.role=role;self.run_dir=Path(run_dir);self.scenario=scenario
        self.rule_input=None;self.rule_engine=None;self.rule_index=0;self.rule_unpublished=False;self.rule_start_file=rule_start_file;self.rule_started=None;self.rule_offset_ms=0
        if scenario=='sample-rules':
            from .rules import Rules
            from .config import ROOT
            cases=json.loads((ROOT/'tests/firmware/rule-vectors.json').read_text(encoding='utf-8'))['cases']
            self.rule_input=next((c for c in cases if c['id']==rule_case and c['role']==role),None)
            if self.rule_input is None:raise ValueError('A matching public role/case is required')
            self.rule_engine=Rules(role)
        self.event_count=events;self.seed=seed;self.received=deque(maxlen=32)
        self.ready=False;self.connected=False;self.subscriptions=set();self.drops=0
        self.connections=0;self.published=0;self.box_count=0;self.model=None
        self.retry=0;self.retry_at=0;self.resubscribe=False;self.rng=random.Random(seed)
        self.protocol=Protocol();self.client=None;self.needs_connect=True
        self.connection_error=None
    def advance_rules(self):
        if self.rule_input is None:return
        if self.rule_started is None:
            if self.rule_start_file:
                if not self.rule_start_file.is_file():return
                self.rule_utc_base=json.loads(self.rule_start_file.read_text())['start_at']
                self.rule_started=self.model.mono()+(self.rule_utc_base-self.model.utc())/1000
                self.rule_offset_ms=round((self.rule_started-self.model.started)*1000)
            else:self.rule_started=self.model.started
        if self.model.mono()<self.rule_started:return
        elapsed=int((self.model.mono()-self.rule_started)*1000);steps=self.rule_input['steps']
        while self.rule_index<len(steps) and steps[self.rule_index]['ms']<=elapsed:
            self.rule_engine.step(steps[self.rule_index]);self.rule_index+=1
        self.rule_engine.poll(elapsed)
        while self.rule_engine.events:
            self.rule_unpublished=True
            event=self.rule_engine.events.pop(0);generated=self.model.event(event['type'],event['data'],event['level'],event['src'])
            self.model.pending[generated['event_seq']].update(ts=self.rule_utc_base+event['uptime_ms'],uptime_ms=self.rule_offset_ms+event['uptime_ms'])
    def property(self):
        out=self.model.property()
        if self.rule_input is None:return out
        engine=self.rule_engine;sample=engine.last_samples.get('env' if self.role=='ENV' else 'pose')
        if sample:
            ms,a=sample;valid=bool(a[3]);out.update(ts=self.rule_utc_base+ms,uptime_ms=self.rule_offset_ms+ms)
            if self.role=='ENV':out['env'].update(temp_c=a[0] if valid else None,humi_pct=a[1] if valid else None,lux=a[2] if valid else None,valid=valid,light_on=engine.lamp)
            else:
                valid=valid and engine.calibrated
                names=['NORMAL','TILTED','SIDEWAYS','INVERTED']
                out['pose'].update(acc_g=a[:3] if valid else None,tilt_deg=engine.tilt if valid else None,state=names[engine.pose or 0] if valid else 'UNKNOWN',valid=valid)
        gps=engine.last_samples.get('gps')
        if not gps and any(step['op']=='gps' for step in self.rule_input['steps']):out['loc']={'lat':None,'lon':None,'valid':False,'src':'MOCK'}
        if gps:
            ms,a=gps;out['loc']={'lat':a[0] if a[3] else None,'lon':a[1] if a[3] else None,'valid':bool(a[3]),'src':'MOCK','observation':{'sample_seq':a[2],'ts':self.rule_utc_base+ms,'ts_src':'SNTP','uptime_ms':self.rule_offset_ms+ms,'time_uncertainty_ms':1}}
        return out
    def topic(self,suffix):return 'box/BOX-0001/'+self.config['accounts'][self.role]['username']+'/'+suffix
    def receive(self,client,userdata,message):
        if len(message.payload)>1536 or len(self.received)==32:
            self.drops+=1;return
        self.received.append((message.topic,bytes(message.payload),message.qos,message.retain))
    def connected_callback(self,client,userdata,flags,reason,properties):
        self.connected=not reason.is_failure;self.ready=False
        if not self.connected:
            self.connection_error='AUTHENTICATION_OR_CONNECTION';return
        self.connection_error=None
        self.connections+=1;self.resubscribe=True
    def disconnected_callback(self,client,userdata,flags,reason,properties):
        self.connected=False;self.ready=False;self.needs_connect=True
    def subscribed_callback(self,client,userdata,mid,reasons,properties):
        if any(r.is_failure for r in reasons):
            self.connected=False;self.ready=False;self.needs_connect=True;return
        self.subscriptions.discard(mid)
        if not self.subscriptions:self.ready=True
    def publish(self,suffix,data):
        raw=pack(data);qos=0 if suffix=='property' else 1
        self.protocol.validate(self.topic(suffix),raw,qos,identity={'box_id':'BOX-0001','node_id':self.model.node},caps=self.model.caps)
        info=self.client.publish(self.topic(suffix),raw,qos=qos,retain=False)
        if info.rc!=mqtt.MQTT_ERR_SUCCESS:return False
        self.published+=1;return True
    def process(self,item):
        topic,raw,qos,retained=item
        suffix=topic.rsplit('/',1)[-1]
        if suffix not in DOWN:
            self.drops+=1;return
        identity={'box_id':'BOX-0001','node_id':self.model.node}
        try:
            data=self.protocol.validate(topic,raw,qos,retained,identity,self.model.caps,
                                        self.model.utc() if suffix=='cmd' else None,
                                        self.model.synced if suffix=='cmd' else None)
            if suffix=='cmd':
                ack=self.model.request(data)
                if ack is not None:self.publish('command_ack',ack)
            elif suffix=='event_ack':self.model.acknowledged(data)
            else:self.model.box_states+=1
        except Rejected as error:
            self.drops+=1
            # Only a safely extractable command on the exact node Topic gets an error response.
            if suffix!='cmd' or topic!=self.topic('cmd'):return
            try:
                data=unpack(raw)
                if data.get('box_id')!='BOX-0001' or data.get('node_id')!=self.model.node:return
                if not all(isinstance(data.get(k),str) and 1<=len(data[k].encode('utf-8'))<=64 for k in ('request_id','operation_id')):return
                self.publish('command_ack',self.model.reject(data,error.code))
            except (Rejected,ValueError,UnicodeError):return
    def save(self):
        atomic_json(self.run_dir/(self.role+'.status.json'),{**self.model.snapshot(),'ready':self.ready,
                    'rule_steps':self.rule_index,'rule_complete':self.rule_input is not None and self.rule_index==len(self.rule_input['steps']),'connections':self.connections,'connection_error':self.connection_error,'retry_count':self.retry,'retry_in_s':max(0,round(self.retry_at-time.monotonic(),3)),'dropped':self.drops,'published':self.published,'pid':__import__('os').getpid()})
    def run(self,stop_file=None):
        self.run_dir.mkdir(parents=True,exist_ok=True)
        node=self.config['accounts'][self.role]['username'];state_dir=Path(self.config['state_dir'])
        with FileLock(state_dir/(node+'.lock')):
            identity=IdentityState(state_dir,node)
            self.model=Model(self.role,identity,self.seed,self.scenario!='unsynced',self.scenario=='invalid')
            self.rule_utc_base=self.model.utc()
            self.client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id=node,clean_session=True,protocol=mqtt.MQTTv311,reconnect_on_failure=False)
            from broker_profiles import configure_client
            configure_client(self.client,self.config)
            self.client.connect_timeout=5
            self.client.username_pw_set(node,self.config['accounts'][self.role]['password'])
            self.client.will_set(self.topic('availability'),pack({'node_id':node,'boot_id':self.model.boot,'online':False}),qos=1,retain=False)
            self.client.on_connect=self.connected_callback;self.client.on_disconnect=self.disconnected_callback
            self.client.on_subscribe=self.subscribed_callback;self.client.on_message=self.receive
            self.client.max_queued_messages_set(32);self.client.max_inflight_messages_set(8)
            next_property=next_heartbeat=next_events=next_save=0
            previously_ready=False;generated=False
            try:
                while not (stop_file and Path(stop_file).exists()):
                    now=time.monotonic()
                    if self.needs_connect and now>=self.retry_at:
                        self.ready=False;previously_ready=False;self.subscriptions.clear()
                        try:
                            self.client.connect(self.config['broker']['host'],self.config['broker']['port'],keepalive=60)
                            self.needs_connect=False
                        except OSError as error:
                            self.connection_error='TLS' if isinstance(error,ssl.SSLError) else 'DNS' if isinstance(error,socket.gaierror) else 'NETWORK'
                        delay=[1,2,4,8,16,30][min(self.retry,5)]
                        self.retry_at=now+delay*(1+self.rng.uniform(0,.2));self.retry+=1
                    result=self.client.loop(timeout=.05)
                    if result!=mqtt.MQTT_ERR_SUCCESS:
                        self.ready=False;self.connected=False;self.needs_connect=True
                    if self.resubscribe:
                        self.resubscribe=False;self.subscriptions.clear()
                        code,mid=self.client.subscribe([(self.topic(x),1) for x in DOWN])
                        if code==mqtt.MQTT_ERR_SUCCESS:self.subscriptions.add(mid)
                        else:self.needs_connect=True
                    self.advance_rules()
                    if self.ready:
                        if not previously_ready:
                            self.retry=0;self.publish('availability',{'node_id':node,'boot_id':self.model.boot,'online':True})
                            next_property=next_heartbeat=next_events=0
                            if not generated:
                                if self.scenario=='sample-rules':pass
                                elif self.scenario.startswith('rules-'):
                                    self.model.rule_events(self.scenario)
                                else:
                                    for _ in range(self.event_count):self.model.event()
                                generated=True
                        while self.received:self.process(self.received.popleft())
                        for ack in self.model.tick():self.publish('command_ack',ack)
                        if now>=next_property:
                            self.publish('property',self.property());next_property=now+1
                        if now>=next_heartbeat:
                            self.publish('heartbeat',self.model.heartbeat());next_heartbeat=now+30
                        if now>=next_events or self.rule_unpublished:
                            for batch in self.model.batches():
                                if self.publish('event',batch):self.model.sent.update(e['event_seq'] for e in batch['events'])
                            self.rule_unpublished=False;next_events=now+5
                    else:
                        # Local simulated deadlines still advance during network loss.
                        self.model.tick()
                    previously_ready=self.ready
                    if now>=next_save:self.save();next_save=now+.2
                    time.sleep(.01)
            except KeyboardInterrupt:pass
            except Exhausted:
                # Stop safely rather than reuse an identity or discard unconfirmed old-generation data.
                self.drops+=1;self.save();raise
            finally:
                if self.ready:
                    self.publish('availability',{'node_id':node,'boot_id':self.model.boot,'online':False})
                    deadline=time.monotonic()+.3
                    while time.monotonic()<deadline:self.client.loop(timeout=.02)
                self.client.disconnect();self.ready=False;self.save()
