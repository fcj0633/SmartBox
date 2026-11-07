"""CR-004 effective definitions, compatibility and drift regressions."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from jsonschema import Draft202012Validator

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import effective_contracts as effective

class EffectiveTests(unittest.TestCase):
    def setUp(self):self.document=effective.definitions()
    def validate(self,name,value):
        Draft202012Validator({'$schema':self.document['$schema'],'$defs':self.document['$defs'],'$ref':'#/$defs/'+name}).validate(value)
    def test_generated_matches(self):self.assertEqual([],effective.check())
    def test_unknown_alarm_and_false_are_distinct(self):
        self.validate('Alarm',{'assessment':'UNKNOWN','active':None,'level':None})
        self.validate('Alarm',{'assessment':'EVALUATED','active':False,'level':None})
        with self.assertRaises(Exception):self.validate('Alarm',{'assessment':'UNKNOWN','active':False,'level':None})
    def test_old_loc_still_accepted(self):self.validate('Loc',{'lat':30,'lon':120,'valid':True,'src':'MOCK'})
    def test_new_loc_uses_existing_time_rules(self):
        loc={'lat':30,'lon':120,'valid':True,'src':'MOCK','observation':{'sample_seq':1,'ts':None,'ts_src':'UNSYNCED','uptime_ms':100,'time_uncertainty_ms':None}}
        self.validate('Loc',loc);loc['observation']['ts']=10
        with self.assertRaises(Exception):self.validate('Loc',loc)
    def test_sample_seq_is_bounded(self):
        loc={'lat':30,'lon':120,'valid':True,'src':'MOCK','observation':{'sample_seq':4294967296,'ts':None,'ts_src':'UNSYNCED','uptime_ms':100,'time_uncertainty_ms':None}}
        with self.assertRaises(Exception):self.validate('Loc',loc)
    def test_drift_and_missing_file_fail_in_temporary_tree(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for relative in ('contracts/common/v1.schema.json','contracts/http/openapi.yaml','contracts/http/ws/client.schema.json','contracts/http/ws/server.schema.json'):
                target=root/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((ROOT/relative).read_bytes())
            directory=root/'contracts/effective';directory.mkdir()
            for name,content in effective.outputs(root).items():(directory/name).write_text(content,encoding='utf-8')
            self.assertEqual([],effective.check(root))
            (directory/'common.schema.json').write_text('{}',encoding='utf-8');self.assertTrue(effective.check(root))
            (directory/'ws-server.schema.json').unlink();self.assertEqual(2,len(effective.check(root)))

if __name__=='__main__':unittest.main()
