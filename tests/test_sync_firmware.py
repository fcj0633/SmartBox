"""All destructive sync cases operate on temporary miniature SDKs."""
from pathlib import Path
import importlib.util
import json
import os
import shutil
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('sync_firmware',ROOT/'tools/sync_firmware.py')
sync=importlib.util.module_from_spec(spec);spec.loader.exec_module(sync)
class SyncChecks(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.source=self.root/'source';shutil.copytree(ROOT/'firmware',self.source)
        self.sdk=self.root/'sdk';self.sdk.mkdir()
        for name in ['bundle.json','build.py']:(self.sdk/name).write_text('fixture')
        self.entry=self.sdk/sync.SAMPLE/'BUILD.gn';self.entry.parent.mkdir(parents=True)
        self.original=b'# fixture\r\nlite_component("app") {\r\nfeatures = [\r\n  "study:study",\r\n]\r\n}\r\n'
        self.entry.write_bytes(self.original)
    def tearDown(self):self.tmp.cleanup()
    def snap(self):
        return {p.relative_to(self.sdk).as_posix():p.read_bytes() for p in self.sdk.rglob('*') if p.is_file()}
    def apply(self,role='ENV'):return sync.synchronize(self.source,self.sdk,role,True)
    def test_preview_zero_writes(self):
        before=self.snap();plan=sync.synchronize(self.source,self.sdk);self.assertTrue(plan['changes']);self.assertEqual(before,self.snap())
    def test_roles_entry_restore_and_unknown_preserved(self):
        self.apply();target=self.sdk/sync.TARGET
        self.assertIn(b'logistics_box/build:smartbox',self.entry.read_bytes())
        unknown=target/'local.txt';unknown.write_text('keep')
        self.apply('POSE');self.assertIn('POSE',(target/'build/selected_role.gni').read_text())
        sync.synchronize(self.source,self.sdk,apply=True,restore=True)
        self.assertEqual(self.original,self.entry.read_bytes());self.assertEqual('keep',unknown.read_text())
        self.apply();self.assertTrue(unknown.exists())
    def test_copy_manual_change_conflict(self):
        self.apply();p=self.sdk/sync.TARGET/'services/sb_clock.c';p.write_text('manual')
        with self.assertRaisesRegex(ValueError,'conflict'):self.apply()
        self.assertEqual('manual',p.read_text())
    def test_unknown_initial_directory(self):
        (self.sdk/sync.TARGET).mkdir()
        with self.assertRaisesRegex(ValueError,'Unknown existing'):self.apply()
    def test_controlled_deletion(self):
        self.apply();p=self.source/'services/sb_network_stub.c';p.unlink();self.apply()
        self.assertFalse((self.sdk/sync.TARGET/'services/sb_network_stub.c').exists())
    def test_modified_deleted_file_not_removed(self):
        self.apply();p=self.sdk/sync.TARGET/'services/sb_network_stub.c';p.write_text('manual')
        (self.source/'services/sb_network_stub.c').unlink()
        with self.assertRaisesRegex(ValueError,'conflict'):self.apply()
        self.assertTrue(p.exists())
    def test_entry_changed_after_restore(self):
        self.apply();sync.synchronize(self.source,self.sdk,apply=True,restore=True)
        self.entry.write_bytes(self.original+b'# changed')
        with self.assertRaisesRegex(ValueError,'entry conflict'):self.apply()
    def test_unknown_new_target_file(self):
        self.apply();(self.source/'include/new.h').write_text('a');(self.sdk/sync.TARGET/'include/new.h').write_text('b')
        with self.assertRaisesRegex(ValueError,'Unknown target'):self.apply()
    def test_overlapping_and_non_sdk_rejected(self):
        with self.assertRaisesRegex(ValueError,'Overlapping'):sync.validate_roots(self.source,self.source)
        (self.sdk/'bundle.json').unlink()
        with self.assertRaisesRegex(ValueError,'Not a supported SDK'):self.apply()
    def test_state_path_escape(self):
        self.apply();p=self.sdk/sync.STATE;s=json.loads(p.read_text());s['files']['../escape']='bad';p.write_text(json.dumps(s))
        with self.assertRaisesRegex(ValueError,'managed path'):self.apply()
    def test_symlink_rejected(self):
        outside=self.root/'outside';outside.mkdir();link=self.source/'linked'
        try:link.symlink_to(outside,target_is_directory=True)
        except OSError:self.skipTest('Windows account cannot create symlinks; exercised on Ubuntu as well')
        with self.assertRaisesRegex(ValueError,'Link/reparse'):self.apply()
    def test_crash_journal_recovery_and_conflict(self):
        self.apply();p=self.sdk/sync.TARGET/'include/sb_types.h';before=p.read_bytes();after=b'new'
        rows=[{'path':p.relative_to(self.sdk).as_posix(),'before':sync.b64(before),'after':sync.b64(after)}]
        journal=self.sdk/sync.JOURNAL;journal.write_bytes(sync.encoded(rows));p.write_bytes(after)
        with self.assertRaisesRegex(ValueError,'Interrupted'):self.apply()
        sync.recover(self.sdk);self.assertEqual(before,p.read_bytes())
        journal.write_bytes(sync.encoded(rows));p.write_bytes(b'concurrent')
        with self.assertRaisesRegex(ValueError,'Recovery conflict'):sync.recover(self.sdk)
        self.assertEqual(b'concurrent',p.read_bytes())
    def test_lock_not_overwritten(self):
        (self.sdk/sync.LOCK).write_text('other')
        with self.assertRaises(FileExistsError):self.apply()
        self.assertEqual('other',(self.sdk/sync.LOCK).read_text())
if __name__=='__main__':unittest.main()
