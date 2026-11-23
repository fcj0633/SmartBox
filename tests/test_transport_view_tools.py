"""Ensure database selection is rejected before any migration/process side effect."""
import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from transport_view import require_test_environment
class TestTransportViewGuard(unittest.TestCase):
    def fixture(self):
        url='jdbc:mysql://127.0.0.1:3306/smartbox_test'
        return dict(SPRING_PROFILES_ACTIVE='test',SMARTBOX_DB_URL=url,SMARTBOX_MIGRATION_URL=url,SMARTBOX_DB_USER='smartbox_test_app',SMARTBOX_MIGRATION_USER='smartbox_test_migrator')
    def test_project_test_config_accepted(self):require_test_environment(self.fixture())
    def test_development_database_rejected(self):
        env=self.fixture();env['SMARTBOX_DB_URL']='jdbc:mysql://127.0.0.1:3306/smartbox_dev'
        with self.assertRaises(ValueError):require_test_environment(env)
    def test_remote_or_wrong_account_rejected(self):
        env=self.fixture();env['SMARTBOX_DB_USER']='root'
        with self.assertRaises(ValueError):require_test_environment(env)
        env=self.fixture();env['SMARTBOX_DB_URL']=env['SMARTBOX_MIGRATION_URL']='jdbc:mysql://remote/smartbox_test'
        with self.assertRaises(ValueError):require_test_environment(env)
    def test_separate_migration_target_rejected(self):
        env=self.fixture();env['SMARTBOX_MIGRATION_URL']='jdbc:mysql://127.0.0.1:3306/smartbox_dev'
        with self.assertRaises(ValueError):require_test_environment(env)
