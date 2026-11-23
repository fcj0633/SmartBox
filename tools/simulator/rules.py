"""Sample-driven rule mirror. Inputs and expected checkpoints are independent fixtures.
No network, persistence, or authoritative door conclusion is produced here.
"""
from __future__ import annotations
import math

class Rules:
    def __init__(self, role):
        self.role=role; self.events=[]; self.last_samples={};self.now=0; self.profile=[2,8,30,85,1,3]; self.profile_id='demo'
        self.metrics=[[0,0,0,0],[0,0,0,0]]; self.open_until=0; self.box_door='UNKNOWN'; self.conclusion=None; self.was_suppressed=False
        self.light=[]; self.light_open=False; self.base=None; self.light_start=[None,None]; self.lamp=False; self.lamp_changed=0; self.lamp_until=0
        self.calibrated=False; self.cal_samples=[]; self.cal_start=None; self.base_vector=None; self.pose=None; self.pending=None; self.pending_since=None; self.tilt=0; self.tilt_start=[None,None]; self.tilt_emitted=[False,False]
        self.impact=[]; self.arm_due=None; self.armed=False; self.revision=0; self.duration=0; self.expires=None; self.latched=False
        self.fence=None; self.seq=None; self.outside=False; self.counts=[0,0]
    def emit(self, kind, data, src):
        level='CRITICAL' if kind=='SENTRY_INTRUSION' else 'WARNING' if kind in ('ENV_HIGH','ENV_LOW','IMPACT','GEOFENCE_EXIT') else 'INFO'
        self.events.append({'type':kind,'level':level,'data':data,'src':src,'uptime_ms':self.now})
    def poll(self, now):
        self.now=now
        if self.arm_due is not None and now>=self.arm_due:
            self.arm_due=None; self.armed=True; self.expires=now+self.duration*1000 if self.duration else None
        if self.expires is not None and now>=self.expires:self.armed=False;self.expires=None;self.latched=False
        if self.lamp and now>=self.lamp_until:self.lamp=False;self.lamp_changed=now
        if self.impact and now-self.impact[-1][0]>=1000:
            self.emit('IMPACT',{'peak_g':max(x[1] for x in self.impact),'duration_ms':self.impact[-1][0]-self.impact[0][0],'quality':'VALID'},'POSE');self.impact=[]
    def alert(self, active, reason):
        if active and self.armed and not self.latched:
            self.lamp=True; self.lamp_changed=self.now; self.lamp_until=self.now+10000
            self.emit('SENTRY_INTRUSION',{'reason':reason,'sentry_revision':self.revision},'LIGHT' if self.role=='ENV' else 'POSE');self.latched=True
        if not active:self.latched=False
    def profile_apply(self, values):
        self.profile=list(values);self.profile_id='TEST';self.metrics=[[v[0],0,0,0] for v in self.metrics]
    def sentry(self, revision,on,duration):
        if revision<=self.revision:return False
        self.revision=revision;self.arm_due=self.now+3000 if on else None;self.armed=False;self.duration=duration;self.expires=None;self.latched=False;return True
    def box(self, opened, identity):
        if identity==self.conclusion:return
        self.conclusion=identity;self.box_door='OPEN' if opened else 'CLOSED';self.open_until=self.now+30000 if opened else 0
    def env(self,temp,humidity,lux,valid=True):
        suppressed=self.box_door=='OPEN' and self.now<self.open_until
        if suppressed!=self.was_suppressed:
            self.metrics=[[v[0],0,0,0] for v in self.metrics];self.was_suppressed=suppressed
        for i,(value,lo,hi,hyst,name) in enumerate(((temp,self.profile[0],self.profile[1],self.profile[4],'temp_c'),(humidity,self.profile[2],self.profile[3],self.profile[5],'humi_pct'))):
            state,enter,recover,direction=self.metrics[i]
            if not valid or suppressed:enter=recover=0
            elif state==0:
                side=1 if value>hi else -1 if value<lo else 0
                enter=enter+1 if side and side==direction else 1 if side else 0;direction=side
                if enter>=3:
                    state=side;enter=0;self.emit('ENV_HIGH' if side>0 else 'ENV_LOW',{'metric':name,'value':value,'threshold':hi if side>0 else lo,'profile_id':self.profile_id},'ENV')
            else:
                threshold=hi-hyst if state>0 else lo+hyst;okay=value<=threshold if state>0 else value>=threshold
                recover=recover+1 if okay else 0
                if recover>=3:state=enter=recover=0;self.emit('ENV_RECOVERED',{'metric':name,'value':value,'threshold':threshold,'profile_id':self.profile_id},'ENV')
            self.metrics[i]=[state,enter,recover,direction]
        if not valid:self.light_start=[None,None];return
        fast=False
        if not self.lamp and self.now>=self.lamp_changed+2000:
            baseline=self.base if self.light_open else sum(x[1] for x in self.light if self.now-x[0]<=5000)/max(1,len([x for x in self.light if self.now-x[0]<=5000]))
            if baseline:
                baseline=max(1,baseline);fast=lux>3*baseline and lux>50
                for idx,condition in enumerate((fast,lux<=1.5*baseline)):
                    self.light_start[idx]=self.now if condition and self.light_start[idx] is None else self.light_start[idx] if condition else None
                    if condition and self.now-self.light_start[idx]>=1500 and self.light_open!=(idx==0):
                        self.emit('DOOR_EVIDENCE',{'action':'OPEN' if idx==0 else 'CLOSE','lux':lux,'actuator_excluded':True},'LIGHT');self.light_open=idx==0;self.base=baseline if idx==0 else None
            if not self.light_open and not fast and self.box_door!='OPEN':self.light=(self.light+[(self.now,lux)])[-5:]
        else:self.light_start=[None,None]
        self.alert(fast,'LIGHT')
    def pose_sample(self,x,y,z,valid=True):
        if not valid or not all(math.isfinite(v) for v in (x,y,z)):
            self.pending_since=None;self.tilt_start=[None,None];self.cal_start=None;self.cal_samples=[];return
        mag=math.sqrt(x*x+y*y+z*z)
        if mag<.1:self.pending_since=None;self.tilt_start=[None,None];self.cal_start=None;self.cal_samples=[];return
        impact=abs(mag-1)>.5
        if impact:
            if not self.calibrated:self.cal_start=None;self.cal_samples=[]
            if self.impact and self.now-self.impact[-1][0]>1000:self.poll(self.now)
            self.impact.append((self.now,mag));self.pending_since=None;self.tilt_start=[None,None];self.alert(True,'IMPACT');return
        if not self.calibrated:
            if not .9<=mag<=1.1:self.cal_start=None;self.cal_samples=[];return
            if self.cal_start is None:self.cal_start=self.now
            self.cal_samples.append((x,y,z))
            if self.now-self.cal_start<3000:return
            avg=[sum(v[i] for v in self.cal_samples)/len(self.cal_samples) for i in range(3)];norm=math.sqrt(sum(v*v for v in avg))
            if norm<.1:return
            self.base_vector=[v/norm for v in avg];self.calibrated=True
        self.tilt=math.degrees(math.acos(max(-1,min(1,sum(v*b for v,b in zip((x,y,z),self.base_vector))/mag))))
        new=0 if self.tilt<10 else 1 if self.tilt<45 else 2 if self.tilt<90 else 3
        if self.pose is None:self.pose=new
        elif new!=self.pose:
            threshold=12 if self.pose==0 else (47 if new>self.pose else 8) if self.pose==1 else (92 if new>self.pose else 43) if self.pose==2 else 88
            crossed=self.tilt>=threshold if new>self.pose else self.tilt<=threshold
            if not crossed:self.pending=self.pose;self.pending_since=None
            elif self.pending!=new or self.pending_since is None:self.pending=new;self.pending_since=self.now
            elif self.now-self.pending_since>=200:
                names=['NORMAL','TILTED','SIDEWAYS','INVERTED'];self.emit('POSE_CHANGED',{'previous_state':names[self.pose],'state':names[new],'tilt_deg':self.tilt},'POSE');self.pose=new
        else:self.pending=new;self.pending_since=None
        self.alert(self.pose!=0,'POSE')
        for i,condition in enumerate((self.tilt>25,self.tilt<10)):
            if not condition:self.tilt_start[i]=None;self.tilt_emitted[i]=False;continue
            if self.tilt_start[i] is None:self.tilt_start[i]=self.now
            if self.now-self.tilt_start[i]>=1500 and not self.tilt_emitted[i]:
                self.emit('DOOR_EVIDENCE',{'action':'OPEN' if i==0 else 'CLOSE','tilt_deg':self.tilt,'actuator_excluded':True},'TILT');self.tilt_emitted[i]=True
    def location(self,lat,lon,seq,valid=True):
        if not valid or not all(math.isfinite(v) for v in (lat,lon)) or not -90<=lat<=90 or not -180<=lon<=180:self.counts=[0,0];return
        if self.seq is not None and seq<=self.seq:return
        self.seq=seq
        if not self.fence:return
        a,b,radius=self.fence;distance=math.hypot((lon-b)*111320*math.cos(math.radians(a)),(lat-a)*111320)
        side=0 if distance>radius else 1 if distance<radius-10 else None
        if side is None:self.counts=[0,0];return
        self.counts[1-side]=0;self.counts[side]+=1
        if self.counts[side]>=3 and self.outside!=(side==0):
            self.outside=side==0;self.emit('GEOFENCE_EXIT' if side==0 else 'GEOFENCE_ENTER',{'lat':lat,'lon':lon,'distance_m':distance,'src':'MOCK'},'MOCK')
            if side==0:self.alert(True,'POSE')
    def step(self, step):
        self.poll(step['ms']);op=step['op'];a=step.get('args',[])
        if op in ('env','pose','gps'):self.last_samples[op]=(self.now,list(a))
        if op=='env':self.env(*a)
        elif op=='pose':self.pose_sample(*a)
        elif op=='gps':self.location(*a)
        elif op=='sentry':self.sentry(*a)
        elif op=='box':self.box(bool(a[0]),str(a[1]))
        elif op=='profile':self.profile_apply(a)
        elif op=='fence':self.fence=a;self.counts=[0,0]
        elif op!='poll':raise ValueError('Unknown rule operation')
