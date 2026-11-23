"""Rule integration guards fail before services or destructive test setup."""
import sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import task008
class RuleIntegrationGuardTests(unittest.TestCase):
 def test_explicit_reset_consent_is_required(self):
  with patch.object(sys,'argv',['task008.py','software','--run-dir',str(ROOT/'artifacts/never-created')]),patch.object(task008,'execute') as execute:
   self.assertEqual(1,task008.main());execute.assert_not_called()
 def test_dev_database_is_rejected_before_any_child(self):
  with tempfile.TemporaryDirectory() as td,patch.object(task008,'ROOT',Path(td)),patch.object(task008,'local_environment',return_value={'SMARTBOX_DB_URL':'jdbc:mysql://127.0.0.1:3306/smartbox_dev','SMARTBOX_DB_USER':'smartbox_app'}),patch.object(task008.subprocess,'Popen') as launch:
   with self.assertRaisesRegex(ValueError,'Project test database'):task008.execute(Path('ignored'),Path(td)/'artifacts/run')
   launch.assert_not_called()
 def test_occupied_port_does_not_stop_unrelated_service(self):
  env={'SMARTBOX_DB_URL':'jdbc:mysql://127.0.0.1:3306/smartbox_test','SMARTBOX_DB_USER':'smartbox_test_app'}
  with tempfile.TemporaryDirectory() as td,patch.object(task008,'ROOT',Path(td)),patch.object(task008,'local_environment',return_value=env),patch.object(task008,'port_open',return_value=True),patch.object(task008,'stop_owned') as stop,patch.object(task008.subprocess,'Popen') as launch:
   with self.assertRaisesRegex(ValueError,'port occupied'):task008.execute(Path('ignored'),Path(td)/'artifacts/run')
   stop.assert_not_called();launch.assert_not_called()
if __name__=='__main__':unittest.main()
