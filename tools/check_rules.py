"""TASK-008 shared input vectors: independent C and Python execution; no services."""
from __future__ import annotations
import argparse,base64,json,sys
from pathlib import Path
from firmware_support import connection_arguments,remote_python
from simulator.rules import Rules
ROOT=Path(__file__).resolve().parents[1]
REMOTE=r"""
import base64,json,pathlib,subprocess,tempfile
with tempfile.TemporaryDirectory(prefix='smartbox-rule-vectors-') as td:
 root=pathlib.Path(td)
 for name,content in payload['files'].items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(base64.b64decode(content))
 result={}
 for role in ('ENV','POSE'):
  binary=root/role
  subprocess.run(['gcc','-std=c99','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-DSB_ROLE_'+role+'=1','-I'+str(root/'firmware/include'),*[str(root/x) for x in payload['sources']],'-lm','-o',str(binary)],check=True)
  subprocess.run([str(binary),'mailbox'],check=True)
  for case in payload['cases']:
   if case['role']!=role:continue
   lines=[' '.join([s['op'],str(s['ms']),*[str(float(a)) for a in s.get('args',[])]]) for s in case['steps']]
   run=subprocess.run([str(binary)],input=('\n'.join(lines)+'\n').encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   if run.returncode:raise RuntimeError(case['id']+' C runner failed: '+run.stderr.decode())
   result[case['id']]=run.stdout.decode()
 print('RESULT '+json.dumps(result))
"""

def python_results(cases):
 results={}
 for case in cases:
  engine=Rules(case['role']);states=[]
  for index,step in enumerate(case['steps']):
   engine.step(step);states.append({'step':index,'armed':engine.armed,'arming':engine.arm_due is not None,'revision':engine.revision,'calibrated':engine.calibrated,'pose':engine.pose or 0})
  results[case['id']]={'events':engine.events,'states':states}
 return results

def verify(cases,python,c):
 from simulator.protocol import Protocol,pack
 protocol=Protocol();checks=[]
 for case in cases:
  name=case['id'];expected=case['expected_types'];py=python[name]
  if [e['type'] for e in py['events']]!=expected:raise AssertionError(name+' Python event sequence')
  rows=[line.split(' ',1) for line in c[name].splitlines()]
  events=[json.loads(raw) for kind,raw in rows if kind=='EVENT'];states=[json.loads(raw) for kind,raw in rows if kind=='STATE']
  if [e['type'] for e in events]!=expected:raise AssertionError(name+' C event sequence')
  if states!=py['states']:raise AssertionError(name+' C/Python state checkpoints disagree')
  for event,reference in zip(events,py['events']):
   stamp={'ts':2000000+event['ms'],'ts_src':'SNTP','uptime_ms':event['ms'],'time_uncertainty_ms':1}
   wire={**stamp,'event_seq':1,'type':event['type'],'src':reference['src'],'level':reference['level'],'replayed':False,'data':event['data']}
   envelope={**stamp,'v':1,'box_id':'BOX-0001','node_id':'BOX-0001-'+case['role'],'boot_id':1,'events':[wire]}
   protocol.validate('box/BOX-0001/'+envelope['node_id']+'/event',pack(envelope),qos=1)
   if event['ms']!=reference['uptime_ms']:raise AssertionError(name+' event time mismatch')
   if event['level']!={'INFO':1,'WARNING':2,'CRITICAL':3}[reference['level']]:raise AssertionError(name+' severity mismatch')
   for key,value in reference['data'].items():
    actual=event['data'].get(key)
    if isinstance(value,(int,float)) and not isinstance(value,bool):
     if actual is None or abs(actual-value)>max(.06,abs(value)*.001):raise AssertionError(name+' numeric data '+key)
    elif actual!=value:raise AssertionError(name+' event data '+key)
  for checkpoint in case.get('checkpoints',[]):
   for key,value in checkpoint.items():
    if key!='step' and states[checkpoint['step']][key]!=value:raise AssertionError(name+' expected checkpoint '+key)
  checks.append({'case':name,'scenarios':case['requirements'],'passed':True,'events':len(events),'checkpoints':len(states)})
 return checks

def main():
 p=argparse.ArgumentParser(description=__doc__);connection_arguments(p);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
 cases=json.loads((ROOT/'tests/firmware/rule-vectors.json').read_text(encoding='utf-8'))['cases'];python=python_results(cases)
 sources=['firmware/'+x for x in ('domain/sb_rules.c','services/sb_rule_control.c','services/sb_clock.c','services/sb_config.c','services/sb_event_buffer.c','drivers/sb_sensor_mock.c','drivers/sb_output_mock.c')]+['tests/firmware/test_rule_vectors.c']
 files={str(f.relative_to(ROOT)).replace('\\','/'):base64.b64encode(f.read_bytes()).decode() for f in (ROOT/'firmware/include').glob('*.h') if f.name!='sb_local_config.h'}
 files.update({x:base64.b64encode((ROOT/x).read_bytes()).decode() for x in sources})
 code,out=remote_python(a,REMOTE,{'files':files,'sources':sources,'cases':cases},check=False)
 a.report.parent.mkdir(parents=True,exist_ok=True);a.report.with_suffix('.log').write_text(out,encoding='utf-8')
 if code:print(out);return code
 c=json.loads(next(line[7:] for line in out.splitlines() if line.startswith('RESULT ')))
 checks=verify(cases,python,c);a.report.write_text(json.dumps({'passed':True,'checks':checks,'mailbox_roles':2},indent=2),encoding='utf-8')
 print('PASS: '+str(len(checks))+' shared C/Python vectors; two mailbox suites');return 0
if __name__=='__main__':raise SystemExit(main())
