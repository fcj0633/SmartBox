"""Independent expected vectors plus protocol validation of generated events."""
import json,sys,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools'))
from simulator.rules import Rules
from simulator.protocol import Protocol,pack
from simulator.model import Model
class Identity:
    node='BOX-0001-ENV';value={'sentry_revision':0}
    def reserve(self):return 1
class SampleRuleTests(unittest.TestCase):
    def test_shared_expected_vectors_and_checkpoint_states(self):
        cases=json.loads((ROOT/'tests/firmware/rule-vectors.json').read_text(encoding='utf-8'))['cases']
        for case in cases:
            with self.subTest(case=case['id']):
                r=Rules(case['role']);states=[]
                for step in case['steps']:
                    r.step(step);states.append({'armed':r.armed,'arming':r.arm_due is not None,'pose':r.pose or 0,'revision':r.revision,'calibrated':r.calibrated})
                self.assertEqual(case['expected_types'],[x['type'] for x in r.events])
                for c in case.get('checkpoints',[]):
                    for key,value in c.items():
                        if key!='step':self.assertEqual(value,states[c['step']][key])
    def test_generated_events_keep_effective_schema_and_capacity(self):
        protocol=Protocol();cases=json.loads((ROOT/'tests/firmware/rule-vectors.json').read_text(encoding='utf-8'))['cases']
        for case in cases:
            identity=Identity();identity.node='BOX-0001-'+case['role'];model=Model(case['role'],identity,monotonic=lambda:1,utc=lambda:2000000);r=Rules(case['role'])
            for step in case['steps']:r.step(step)
            for event in r.events:model.event(event['type'],event['data'],event['level'],event['src'])
            for batch in model.batches():protocol.validate('box/BOX-0001/'+identity.node+'/event',pack(batch),qos=1)
    def test_sample_runtime_requires_matching_role_and_public_case(self):
        from simulator.runtime import Node
        with self.assertRaises(ValueError):Node({},'POSE',ROOT/'artifacts',scenario='sample-rules',rule_case='env-high-recover')
if __name__=='__main__':unittest.main()
