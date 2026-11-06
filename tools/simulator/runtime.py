"""One process/one thread owns each MQTT client, including all its callbacks."""
from __future__ import annotations
from collections import deque
import json
from pathlib import Path
import random
import time
import paho.mqtt.client as mqtt
from .config import DOWN
from .model import Model, Exhausted
from .protocol import Protocol, pack, unpack, Rejected
from .storage import IdentityState, FileLock, atomic_json

class Node:
    def __init__(self,config,role,run_dir,scenario='normal',events=1,seed=1):
        self.config=config;self.role=role;self.run_dir=Path(run_dir);self.scenario=scenario
        self.event_count=events;self.seed=seed;self.received=deque(maxlen=32)
        self.ready=False;self.connected=False;self.subscriptions=set();self.drops=0
        self.connections=0;self.published=0;self.box_count=0;self.model=None
        self.retry=0;self.retry_at=0;self.resubscribe=False;self.rng=random.Random(seed)
        self.protocol=Protocol();self.client=None;self.needs_connect=True
    def topic(self,suffix):return 'box/BOX-0001/'+self.config['accounts'][self.role]['username']+'/'+suffix
    def receive(self,client,userdata,message):
        if len(message.payload)>1536 or len(self.received)==32:
            self.drops+=1;return
        self.received.append((message.topic,bytes(message.payload),message.qos,message.retain))
    def connected_callback(self,client,userdata,flags,reason,properties):
        self.connected=not reason.is_failure;self.ready=False
        if not self.connected:return
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
                    'connections':self.connections,'retry_count':self.retry,'retry_in_s':max(0,round(self.retry_at-time.monotonic(),3)),'dropped':self.drops,'published':self.published,'pid':__import__('os').getpid()})
    def run(self,stop_file=None):
        self.run_dir.mkdir(parents=True,exist_ok=True)
        node=self.config['accounts'][self.role]['username'];state_dir=Path(self.config['state_dir'])
        with FileLock(state_dir/(node+'.lock')):
            identity=IdentityState(state_dir,node)
            self.model=Model(self.role,identity,self.seed,self.scenario!='unsynced',self.scenario=='invalid')
            self.client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id=node,clean_session=True,protocol=mqtt.MQTTv311,reconnect_on_failure=False)
            self.client.connect_timeout=3
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
                            self.client.connect('127.0.0.1',1883,keepalive=60)
                            self.needs_connect=False
                        except OSError:pass
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
                    if self.ready:
                        if not previously_ready:
                            self.retry=0;self.publish('availability',{'node_id':node,'boot_id':self.model.boot,'online':True})
                            next_property=next_heartbeat=next_events=0
                            if not generated:
                                for _ in range(self.event_count):self.model.event()
                                generated=True
                        while self.received:self.process(self.received.popleft())
                        for ack in self.model.tick():self.publish('command_ack',ack)
                        if now>=next_property:
                            self.publish('property',self.model.property());next_property=now+1
                        if now>=next_heartbeat:
                            self.publish('heartbeat',self.model.heartbeat());next_heartbeat=now+30
                        if now>=next_events:
                            for batch in self.model.batches():
                                if self.publish('event',batch):self.model.sent.update(e['event_seq'] for e in batch['events'])
                            next_events=now+5
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
