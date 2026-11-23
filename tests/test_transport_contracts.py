"""Transport contract failure branches use tiny temporary public fixtures only."""
import json,sys,shutil,tempfile,unittest
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
from check_transport_contracts import check,validate_value

class TransportContracts(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.base=self.root/'contracts/http/transport';shutil.copytree(ROOT/'contracts/http/transport',self.base)
    def tearDown(self):self.tmp.cleanup()
    def change_api(self,mutation):
        p=self.base/'openapi.yaml';api=yaml.safe_load(p.read_text(encoding='utf-8'));mutation(api);p.write_text(yaml.safe_dump(api),encoding='utf-8')
    def test_valid_public_contract(self):self.assertEqual(6,check(self.root)['operations'])
    def test_missing_route_fails(self):
        self.change_api(lambda a:a['paths'].pop('/rider/transports/{id}/finish'))
        with self.assertRaises(ValueError):check(self.root)
    def test_anonymous_operation_rejected(self):
        self.change_api(lambda a:a['paths']['/rider/transports']['get'].update(security=[]))
        with self.assertRaises(ValueError):check(self.root)
    def test_broken_local_reference_fails(self):
        self.change_api(lambda a:a['paths']['/rider/transports']['get']['responses']['200']['content']['application/json'].update(schema={'$ref':'missing.json'}))
        with self.assertRaises(Exception):check(self.root)
    def test_sensitive_or_forged_fields_not_part_of_contract(self):
        with self.assertRaises(Exception):validate_value('CreateRequest',{'box_id':'BOX-0001','rider_id':1},self.root)
        with self.assertRaises(Exception):validate_value('TransportResponse',{'data':{},'request_id':'test','password_hash':'private'},self.root)
if __name__=='__main__':unittest.main()
