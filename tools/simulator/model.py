"""Deterministic host simulation. Pending events are intentionally VOLATILE."""
from __future__ import annotations
from collections import OrderedDict
import copy
import random
import time
from .protocol import pack, Rejected
from .storage import U32

class Exhausted(ValueError): pass

class Model:
    def __init__(self, role, identity, seed=1, synced=True, invalid=False,
                 monotonic=time.monotonic, utc=lambda:int(time.time()*1000)):
        self.role=role; self.identity=identity; self.node=identity.node; self.box='BOX-0001'
        self.caps=71 if role=='ENV' else 120
        self.mono=monotonic; self.utc=utc; self.started=self.mono()
        self.boot=identity.reserve(); self.synced=synced; self.invalid=invalid
        self.seed=seed; self.location_step=0; self.mode='FIXED'; self.profile_id='SIM-DEFAULT'; self.profile=None
        self.rng=random.Random(seed)
        self.outputs={'light':False,'fan':False,'indicator':False}; self.output_due={}
        self.revision=identity.value['sentry_revision']
        self.sentry='DEGRADED' if self.revision else 'DISARMED'
        self.arm=None; self.arm_due=None; self.expire_due=None; self.calibrated=False
        self.cache=OrderedDict(); self.pending=OrderedDict(); self.seq=0; self.property_seq=0
        self.overflow=0; self.lost=[]; self.actions=0; self.rejects=0; self.box_states=0
        self.sent=set()

    def stamp(self):
        return {'ts':self.utc() if self.synced else None,'ts_src':'SNTP' if self.synced else 'UNSYNCED',
                'uptime_ms':int((self.mono()-self.started)*1000), 'time_uncertainty_ms':1 if self.synced else None}
    def up(self):
        return {'v':1,'box_id':self.box,'node_id':self.node,'boot_id':self.boot,**self.stamp()}
    def property(self):
        if self.property_seq==U32: raise Exhausted('property sequence exhausted')
        self.property_seq+=1
        out={**self.up(),'role':self.role,'caps':self.caps,'property_seq':self.property_seq}
        valid=not self.invalid
        if self.role=='ENV':
            out['env']={'temp_c':round(20+self.rng.random(),3) if valid else None,
                        'humi_pct':round(50+self.rng.random(),3) if valid else None,
                        'lux':20 if valid else None,'valid':valid,
                        'light_on':self.outputs['light'],'fan_on':self.outputs['fan']}
        else:
            out['pose']={'acc_g':[0,0,1] if valid else None,'tilt_deg':0 if valid else None,
                         'state':'NORMAL' if valid else 'UNKNOWN','valid':valid}
            self.location_step+=1
            step=(0 if self.mode=='FIXED' else
                  ((self.location_step+self.seed)%20)*0.00001 if self.mode=='ROUTE' else
                  self.rng.uniform(-0.0001,0.0001))
            out['loc']={'lat':round(30+step,7) if valid else None,
                        'lon':round(120+step,7) if valid else None,'valid':valid,'src':'MOCK'}
        return out
    def heartbeat(self):
        return {**self.up(),'role':self.role,'caps':self.caps,
                'sentry':{'state':self.sentry,'revision':self.revision},
                'sensor_quality':'INVALID' if self.invalid else 'VALID',
                'pending_events':len(self.pending),'overflow_total':self.overflow,
                'lost_ranges':copy.deepcopy(self.lost),'storage':'DEGRADED'}
    def event(self):
        if self.seq==U32: raise Exhausted('event sequence exhausted')
        self.seq+=1
        event={**self.stamp(),'event_seq':self.seq,'type':'DEVICE_FAULT','src':'SIMULATOR',
               'level':'INFO','data':{'component':'simulation','code':'TEST_INPUT'},'replayed':False}
        if len(self.pending)==64:
            seq,_=self.pending.popitem(last=False);self.sent.discard(seq);self.overflow+=1
            if self.lost and self.lost[-1]['last_seq']+1==seq:
                self.lost[-1]['last_seq']=seq
            else:self.lost.append({'boot_id':self.boot,'first_seq':seq,'last_seq':seq})
        self.pending[self.seq]=event
        return copy.deepcopy(event)
    def batches(self):
        batch=[]
        for seq,event in self.pending.items():
            item=copy.deepcopy(event);item['replayed']=seq in self.sent
            candidate={**self.up(),'events':batch+[item]}
            if batch and (len(batch)==4 or len(pack(candidate))>1536):
                yield {**self.up(),'events':batch};batch=[]
            batch.append(item)
        if batch:yield {**self.up(),'events':batch}
    def acknowledged(self,data):
        if data['boot_id']!=self.boot: raise Rejected()
        for seq in data['event_seqs']:
            self.pending.pop(seq,None);self.sent.discard(seq)
    def ack(self,command,result='ok',code='completed',actual=None):
        return {**self.up(),'request_id':command['request_id'],'operation_id':command['operation_id'],
                'result':result,'code':code,'actual_state':actual or {}}
    def reject(self,command,code):
        self.rejects+=1
        return self.ack(command,'expired' if code=='expired' else 'error',code)
    def request(self,command):
        key=command['request_id']
        fingerprint={k:copy.deepcopy(command[k]) for k in ['v','request_id','operation_id','box_id','node_id','cmd','target_caps','issued_at','expires_at','params']}
        if key in self.cache:
            previous=self.cache[key]
            if fingerprint!=previous[0]: return self.reject(command,'conflicting_request')
            return copy.deepcopy(previous[1])
        # Never evict an in-progress action to make room for another request.
        if len(self.cache)>=32:
            done=next((k for k,v in self.cache.items() if v[1] is not None),None)
            if done is None: return self.ack(command,'busy','invalid_params')
            del self.cache[done]
        kind=command['cmd'];params=command['params'];actual={}
        if kind=='sentry':
            if self.arm is not None:return self.ack(command,'busy','invalid_params')
            rev=params['sentry_revision']
            if rev<=self.revision:return self.reject(command,'stale_revision')
            try:self.identity.revision(rev)
            except (ValueError,OSError):return self.reject(command,'storage_error')
            self.revision=rev;self.expire_due=None
            if params['on']:
                self.sentry='ARMING';self.arm=copy.deepcopy(command);self.arm_due=self.mono()+3
                self.cache[key]=(fingerprint,None);self.actions+=1
                return None
            self.sentry='DISARMED';actual={'sentry':self.sentry,'sentry_revision':self.revision}
        elif kind=='output':
            output=params['kind'];self.outputs[output]=params['on']
            self.output_due[output]=self.mono()+params['duration_s']
            actual={'kind':output,'on':params['on']}
        elif kind=='profile':
            self.profile=copy.deepcopy(params);self.profile_id=params['profile_id'];actual={'profile_id':self.profile_id}
        elif kind=='gps_mode':
            self.mode=params['mode'];self.rng=random.Random(params['seed']);actual={'mode':self.mode}
        elif kind=='calibrate':
            self.calibrated=not self.invalid
            if not self.calibrated:return self.ack(command,'busy','invalid_params')
            actual={'calibrated':True}
        else:return self.reject(command,'unsupported_command')
        self.actions+=1;result=self.ack(command,actual=actual)
        self.cache[key]=(fingerprint,copy.deepcopy(result));return result
    def tick(self):
        completed=[];now=self.mono()
        for output,deadline in list(self.output_due.items()):
            if now>=deadline:self.outputs[output]=False;del self.output_due[output]
        if self.arm is not None and now>=self.arm_due:
            command=self.arm;self.arm=None;self.sentry='ARMED'
            duration=command['params']['duration_s']
            # CR-003: the full protection duration starts at actual ARMED, not acceptance.
            self.expire_due=now+duration if duration else None
            ack=self.ack(command,actual={'sentry':'ARMED','sentry_revision':self.revision})
            self.cache[command['request_id']]=(self.cache[command['request_id']][0],copy.deepcopy(ack))
            completed.append(ack)
        if self.expire_due is not None and now>=self.expire_due:
            self.sentry='DISARMED';self.expire_due=None
        return completed
    def snapshot(self):
        return {'node_id':self.node,'boot_id':self.boot,'pending_events':len(self.pending),
                'event_seq':self.seq,'property_seq':self.property_seq,'sentry':self.sentry,
                'revision':self.revision,'outputs':dict(self.outputs),'actions':self.actions,
                'rejects':self.rejects,'cache_size':len(self.cache),'overflow_total':self.overflow,
                'box_states':self.box_states,'storage':'DEGRADED'}
