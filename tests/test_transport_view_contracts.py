import json,sys,shutil,tempfile,unittest
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
from check_transport_view_contracts import check,validate_value
class TransportViewContracts(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.base=self.root/'contracts/http/transport-view';shutil.copytree(ROOT/'contracts/http/transport-view',self.base)
    def tearDown(self):self.temp.cleanup()
    def change(self,fn):
        file=self.base/'openapi.yaml';value=yaml.safe_load(file.read_text(encoding='utf-8'));fn(value);file.write_text(yaml.safe_dump(value),encoding='utf-8')
    def test_valid(self):self.assertEqual(8,check(self.root)['operations'])
    def test_missing_operation(self):
        self.change(lambda d:d['paths'].pop('/enterprise/riders'))
        with self.assertRaises(ValueError):check(self.root)
    def test_public_scope_cannot_expand(self):
        self.change(lambda d:d['paths']['/enterprise/transports']['get'].update(security=[]))
        with self.assertRaises(ValueError):check(self.root)
    def test_broken_reference(self):
        self.change(lambda d:d['paths']['/public/transport-view']['get']['responses']['200']['content']['application/json'].update(schema={'$ref':'missing.json'}))
        with self.assertRaises(Exception):check(self.root)
    def test_verify_cannot_select_arbitrary_task(self):
        with self.assertRaises(Exception):validate_value('VerifyRequest',{'transport_no':'TR-example','code':'123','box_id':'BOX-0002'},self.root)
