"""Contract-tool regression tests; mutations affect temporary copies only."""
from __future__ import annotations
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import yaml

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('smartbox_contracts', ROOT / 'tools/check_contracts.py')
checker = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = checker
spec.loader.exec_module(checker)


class ContractChecks(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='smartbox-contract-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        shutil.copytree(ROOT / 'contracts', self.root / 'contracts')

    def load(self, name):
        return json.loads((self.root / name).read_text(encoding='utf-8'))

    def save(self, name, data):
        (self.root / name).write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')

    def fail_with(self, fragment):
        errors = checker.validate(self.root).errors
        self.assertTrue(any(fragment in x for x in errors), errors)

    def test_complete_contracts_pass(self):
        result = checker.validate(self.root)
        self.assertEqual([], result.errors)
        self.assertEqual(11, result.stats['http_operations'])
        self.assertGreater(result.stats['negative'], 60)

    def test_unknown_reference_fails(self):
        p = 'contracts/mqtt/schemas/property.schema.json'
        s = self.load(p); s['$ref'] = '../../common/v1.schema.json#/$defs/Missing'
        self.save(p, s)
        self.fail_with('Missing')

    def test_remote_reference_rejected_without_network(self):
        p = 'contracts/http/openapi.yaml'
        s = yaml.safe_load((self.root / p).read_text(encoding='utf-8'))
        s['components']['schemas']['Error'] = {'$ref': 'https://example.invalid/error.json'}
        (self.root / p).write_text(yaml.safe_dump(s), encoding='utf-8')
        self.fail_with('Non-local OpenAPI reference')

    def test_missing_operation_coverage(self):
        p = 'contracts/coverage.json'; s = self.load(p); s['http_operations'].pop()
        self.save(p, s); self.fail_with('Coverage HTTP inventory')

    def test_duplicate_operation(self):
        p = 'contracts/http/openapi.yaml'; s = yaml.safe_load((self.root / p).read_text(encoding='utf-8'))
        s['paths']['/me']['get']['operationId'] = 'login'
        (self.root / p).write_text(yaml.safe_dump(s), encoding='utf-8')
        self.fail_with('route inventory')

    def test_response_schema_drift(self):
        p = 'contracts/http/openapi.yaml'; s = yaml.safe_load((self.root / p).read_text(encoding='utf-8'))
        s['paths']['/me']['get']['responses']['200']['content']['application/json']['schema'] = {'$ref':'#/components/schemas/LoginResponse'}
        (self.root / p).write_text(yaml.safe_dump(s), encoding='utf-8')
        self.fail_with('response schema mapping mismatch')

    def test_authentication_bypass(self):
        p = 'contracts/http/openapi.yaml'; s = yaml.safe_load((self.root / p).read_text(encoding='utf-8'))
        s['paths']['/me']['get']['security'] = []
        (self.root / p).write_text(yaml.safe_dump(s), encoding='utf-8')
        self.fail_with('bypasses authentication')

    def test_wrong_command_parameter(self):
        p = 'contracts/mqtt/fixtures/positive/command-output.json'; s = self.load(p)
        s['params']['duration_s'] = 31; self.save(p, s)
        self.fail_with('command-output: unexpected')

    def test_missing_command_positive_coverage(self):
        p = 'contracts/coverage.json'; s = self.load(p)
        s['fixtures'] = [x for x in s['fixtures'] if x['id'] != 'command-calibrate']
        self.save(p, s); self.fail_with('Command positive/negative coverage')

    def test_false_negative_expectation(self):
        p = 'contracts/coverage.json'; s = self.load(p)
        s['fixtures'][1]['expected']['code'] = 'impossible'
        self.save(p, s); self.fail_with('expected')

    def test_wrong_error_location(self):
        p = 'contracts/coverage.json'; s = self.load(p)
        s['fixtures'][1]['expected']['path'] = '/not-the-invalid-field'
        self.save(p, s); self.fail_with('expected')

    def test_missing_fixture(self):
        (self.root / 'contracts/mqtt/fixtures/positive/command-profile.json').unlink()
        self.fail_with('FileNotFoundError')

    def test_utf8_boundary(self):
        p = self.root / 'contracts/mqtt/fixtures/positive/payload-1536.json'
        self.assertEqual(1536, len(p.read_bytes()))
        p.write_bytes(p.read_bytes() + b' ')
        self.fail_with('payload-1536: unexpected')

    def test_delivery_nested_evidence(self):
        p = 'contracts/http/fixtures/positive/delivery-latest.json'; s = self.load(p)
        s['data']['extension'] = {'evidence_json': {'secret': 'raw'}}
        self.save(p, s); self.fail_with('delivery-latest: unexpected')

    def test_subscription_partial_unauthorized(self):
        p = 'contracts/http/fixtures/positive/ws-subscribe.json'; s = self.load(p)
        s['box_ids'].append('OTHER'); self.save(p, s)
        self.fail_with('box_scope')

    def test_csv_formula_rejected(self):
        p = self.root / 'contracts/http/fixtures/positive/http-export.csv'
        p.write_text('id,box_id,type\n=1+1,BOX-0001,ALARM\n', encoding='utf-8')
        self.fail_with('csv_formula')

    def test_unknown_mqtt_fields_stay_compatible(self):
        p = 'contracts/mqtt/fixtures/positive/mqtt-property.json'; s = self.load(p)
        s['future_extension'] = {'version': 'future'}; self.save(p, s)
        self.assertEqual([], checker.validate(self.root).errors)

    def test_forged_precise_ack(self):
        p = 'contracts/mqtt/fixtures/positive/mqtt-event_ack.json'; s = self.load(p)
        s['event_seqs'].append(2); self.save(p, s)
        self.fail_with('ack_set')

    def test_invalid_json_never_repaired(self):
        for raw in [b'{"v":NaN}', b'{"v":1e999}', b'{"v":1,"v":2}', b'{', b'\xff']:
            with self.subTest(raw=raw):
                with self.assertRaises((ValueError, UnicodeError)):
                    checker.strict_json(raw)

    def test_null_root_is_not_a_message(self):
        p = self.root / 'contracts/mqtt/fixtures/positive/mqtt-property.json'
        p.write_text('null', encoding='utf-8')
        self.fail_with('mqtt-property: unexpected')

    def test_completion_code_must_match_success(self):
        p = 'contracts/mqtt/fixtures/positive/mqtt-command_ack.json'; s = self.load(p)
        s['code'] = 'unsupported_caps'; self.save(p, s)
        self.fail_with('mqtt-command_ack: unexpected')

    def test_delivery_event_payload_not_raw_evidence(self):
        p = 'contracts/http/fixtures/positive/delivery-events.json'; s = self.load(p)
        s['data']['items'][0]['data']['lux'] = 80
        self.save(p, s); self.fail_with('delivery-events: unexpected')

    def test_event_capability_filter(self):
        p = 'contracts/coverage.json'; s = self.load(p)
        for case in s['fixtures']:
            if case['id'] == 'event-impact':
                case['context'] = {'caps': 71}
        self.save(p, s); self.fail_with('event_caps')

    def test_missing_interface_requirement_source(self):
        p = 'contracts/coverage.json'; s = self.load(p)
        del s['sources']['mqtt:heartbeat']
        self.save(p, s); self.fail_with('Interface source inventory incomplete')


if __name__ == '__main__':
    unittest.main()
