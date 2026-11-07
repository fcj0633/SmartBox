"""Frontend runner safety tests use temporary paths and mocked process boundaries only."""
from __future__ import annotations
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import frontend
import frontend_integration as integration

class FrontendToolChecks(unittest.TestCase):
    def test_browser_environment_strips_inherited_secrets(self):
        with patch.dict('os.environ',{'SMARTBOX_DB_PASSWORD':'unit-secret','VITE_PASSWORD':'unit-secret','MYSQL_PWD':'unit-secret','PATH':'tools'},clear=True):
            env=frontend.frontend_environment()
        self.assertEqual('tools',env['PATH'])
        self.assertNotIn('SMARTBOX_DB_PASSWORD',env);self.assertNotIn('VITE_PASSWORD',env);self.assertNotIn('MYSQL_PWD',env)
        self.assertIn('artifacts',env['PLAYWRIGHT_BROWSERS_PATH'])
    def test_integration_requires_explicit_test_profile(self):
        with patch.object(sys,'argv',['frontend.py','integration','--allow-test-reset','--run-dir','artifacts/test']),patch('frontend_integration.integrate') as execute:
            self.assertEqual(1,frontend.main());execute.assert_not_called()
    def test_integration_requires_explicit_reset(self):
        with patch.object(sys,'argv',['frontend.py','integration','--profile','test','--run-dir','artifacts/test']),patch('frontend_integration.integrate') as execute:
            self.assertEqual(1,frontend.main());execute.assert_not_called()
    def test_existing_evidence_directory_rejected_before_configuration(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(integration,'ROOT',Path(temp)):
            folder=Path(temp)/'artifacts/existing';folder.mkdir(parents=True)
            with self.assertRaises(ValueError):integration.integrate(Path(temp)/'no-secret.json',folder)
    def test_path_outside_artifacts_rejected(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(integration,'ROOT',Path(temp)):
            with self.assertRaises(ValueError):integration.integrate(Path(temp)/'no-secret.json',Path(temp)/'outside')
    def test_occupied_port_rejected_without_creating_evidence(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(integration,'ROOT',Path(temp)),patch.object(integration,'port_open',return_value=True):
            target=Path(temp)/'artifacts/new'
            with self.assertRaises(ValueError):integration.integrate(Path(temp)/'no-secret.json',target)
            self.assertFalse(target.exists())
    def test_database_name_near_match_is_not_allowed(self):
        stack=object.__new__(integration.BrowserStack)
        stack.env={'SMARTBOX_DB_URL':'jdbc:mysql://localhost:3306/smartbox_test_other','SMARTBOX_DB_USER':'smartbox_test_app'}
        with self.assertRaises(ValueError):stack.sql('DELETE FROM users')
    def test_stopping_an_already_exited_child_does_not_touch_other_processes(self):
        child=Mock();child.poll.return_value=0
        with patch.object(frontend.subprocess,'run') as execute:frontend.stop_owned(child);execute.assert_not_called()

if __name__=='__main__':unittest.main()
