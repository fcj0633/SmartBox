"""Asset CLI guards: temporary local inputs; no MySQL, HTTP or process start."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import assets
import backend


class AssetToolGuards(unittest.TestCase):
    def test_existing_different_seed_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / 'bootstrap.json'
            fixture.write_text(json.dumps({'users':[
                {'username':'admin','role':'LOGISTICS_OPERATOR','box_ids':['BOX-0001']},
                {'username':'rider','role':'COURIER','box_ids':['BOX-0001']}]}))
            target = root / 'assets.json'
            original = '{"rider_usernames": ["someone-else"]}'
            target.write_text(original)
            with patch.object(assets, 'local_environment', return_value={'SMARTBOX_BOOTSTRAP_FILE':str(fixture)}), patch.object(assets, 'seed_path', return_value=target):
                with self.assertRaises(ValueError):
                    assets.prepare('dev', root / 'config.json')
            self.assertEqual(original, target.read_text())

    def test_dev_profile_cannot_run_destructive_tests(self):
        with patch.object(sys, 'argv', ['assets.py','test','--profile','dev','--allow-test-reset']), patch.object(assets, 'environment', return_value={}), patch.object(assets.subprocess, 'call') as call:
            self.assertEqual(1, assets.main())
            call.assert_not_called()

    def test_test_profile_requires_reset_consent(self):
        with patch.object(sys, 'argv', ['assets.py','test','--profile','test']), patch.object(assets, 'environment', return_value={}), patch.object(assets.subprocess, 'call') as call:
            self.assertEqual(1, assets.main())
            call.assert_not_called()

    def test_existing_backend_test_entry_passes_asset_config(self):
        with patch.object(sys, 'argv', ['backend.py','test','--profile','test','--allow-test-reset']), patch.object(backend, 'local_environment', return_value={}), patch.object(assets, 'prepare', return_value=Path('test-assets.json')) as prepare, patch.object(backend.subprocess, 'run') as run:
            run.return_value.returncode = 0
            self.assertEqual(0, backend.main())
            prepare.assert_called_once()
            env = run.call_args.kwargs['env']
            self.assertEqual('test-assets.json', env['SMARTBOX_ASSET_SEED_FILE'])
            self.assertEqual('true', env['SMARTBOX_TEST_ALLOW_RESET'])


if __name__ == '__main__':
    unittest.main()
