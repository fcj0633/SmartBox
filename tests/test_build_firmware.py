"""Backup/recovery regressions use miniature temporary SDKs, never the real SDK."""
from pathlib import Path
import hashlib
import json
import shutil
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
ns={};exec(compile((ROOT/'tools/firmware_remote.py').read_text().split("root=validated(payload['sdk'])")[0],'<worker-test>','exec'),ns)
class BuildRecoveryChecks(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()/'sdk';self.root.mkdir()
        for name in ['build.py','bundle.json',ns['SAMPLE'],ns['OUT']+'/old.bin',ns['VENDOR']+'/output/bin/old.out']:
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'original')
        (self.root/ns['SAMPLE']).chmod(0o755)
        self.backup=ns['backup'](self.root);self.work=Path(self.backup['work'])
        self.state=json.loads((self.work/'state.json').read_text())
    def tearDown(self):
        shutil.rmtree(self.work);self.tmp.cleanup()
    def test_restore_owned_build_changes_preserves_bytes_modes(self):
        p=self.root/ns['OUT']/'old.bin';p.write_bytes(b'build result')
        (self.root/ns['VENDOR']/'generated').write_bytes(b'generated')
        ns['capture'](self.root,self.work,self.state)
        result=ns['restore'](self.root,self.work,self.state)
        self.assertTrue(result['restored']);self.assertEqual(b'original',p.read_bytes())
        self.assertFalse((self.root/ns['VENDOR']/'generated').exists())
        self.assertEqual(self.state['before'],ns['manifest'](self.root))
    def test_concurrent_change_refuses_before_delete(self):
        p=self.root/ns['OUT']/'old.bin';p.write_bytes(b'manual')
        with self.assertRaisesRegex(RuntimeError,'Concurrent'):ns['restore'](self.root,self.work,self.state)
        self.assertEqual(b'manual',p.read_bytes());self.assertTrue((self.root/'.smartbox-build.lock').exists())
    def test_unknown_interrupted_output_ownership_refuses(self):
        self.state['expected']=None
        with self.assertRaisesRegex(RuntimeError,'unknown output ownership'):ns['restore'](self.root,self.work,self.state)
    def test_corrupt_backup_refuses_before_delete(self):
        p=self.work/'original-sdk.tar';p.write_bytes(p.read_bytes()+b'bad')
        with self.assertRaisesRegex(RuntimeError,'checksum'):ns['restore'](self.root,self.work,self.state)
        self.assertTrue((self.root/ns['OUT']/'old.bin').exists())
    def test_build_lock_excludes_second_backup(self):
        with self.assertRaises(FileExistsError):ns['backup'](self.root)
    def test_sdk_and_target_boundary(self):
        with self.assertRaises(RuntimeError):ns['validated'](self.root/'not-sdk')
        with self.assertRaises(RuntimeError):ns['validated'](Path('/'))
    def test_restored_entry_bytes_with_controlled_mode(self):
        p=self.root/ns['SAMPLE'];mode=p.stat().st_mode & 0o777
        self.state['before'][ns['SAMPLE']]['mode']=mode
        p.chmod(0o664)
        self.state['expected'][ns['SAMPLE']]=dict(self.state['before'][ns['SAMPLE']],mode=p.stat().st_mode & 0o777,sha256='previous-selected-entry')
        result=ns['restore'](self.root,self.work,self.state)
        self.assertTrue(result['restored'])
    def test_backup_is_complete_and_hashed(self):
        self.assertEqual(self.backup['sha256'],hashlib.sha256((self.work/'original-sdk.tar').read_bytes()).hexdigest())
        self.assertEqual(3,self.backup['file_count'])
if __name__=='__main__':unittest.main()
