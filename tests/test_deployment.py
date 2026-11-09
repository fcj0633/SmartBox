"""Deployment negative paths use temporary trees, never live databases or SDKs."""
import base64,hashlib,io,importlib.util,json,os,sys,tarfile,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools'))
import development_config as dc
import backend
import project

class DeploymentTests(unittest.TestCase):
 def test_initialize_is_idempotent_and_secret_paths_are_portable(self):
  with tempfile.TemporaryDirectory(prefix='部署 空格-') as d:
   root=Path(d);self.assertTrue(dc.initialize(root,13306))
   first=(root/'secrets/backend.local.json').read_bytes()
   self.assertFalse(dc.initialize(root,13306));self.assertEqual(first,(root/'secrets/backend.local.json').read_bytes())
   env=json.loads(first)['dev'];self.assertIn(':13306/',env['SMARTBOX_DB_URL'])
   self.assertFalse(Path(env['SMARTBOX_BOOTSTRAP_FILE']).is_absolute())
   self.assertEqual(32,len(base64.b64decode(env['SMARTBOX_JWT_SECRET_BASE64'])))
 def test_unknown_partial_bootstrap_is_not_overwritten(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p=root/'secrets/backend-dev.bootstrap.local.json';p.parent.mkdir();p.write_text('preserve')
   with self.assertRaises(ValueError):dc.initialize(root)
   self.assertEqual('preserve',p.read_text())
 def test_missing_bootstrap_never_reports_initialization_complete(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);dc.initialize(root);(root/'secrets/backend-test.bootstrap.local.json').unlink()
   with self.assertRaises(ValueError):dc.initialize(root)
 def test_interrupted_initialization_resumes_reserved_credentials(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);original=dc.write_new;count=0
   def interrupted(path,value):
    nonlocal count
    count+=1
    if count==3:raise OSError('injected interruption')
    return original(path,value)
   with patch.object(dc,'write_new',side_effect=interrupted):
    with self.assertRaises(OSError):dc.initialize(root)
   reserved=(root/'secrets/backend.local.json').read_bytes()
   self.assertTrue(dc.initialize(root));self.assertEqual(reserved,(root/'secrets/backend.local.json').read_bytes())
   self.assertFalse((root/'secrets/development-init.pending.local.json').exists())
 def test_invalid_ports(self):
  with tempfile.TemporaryDirectory() as d:
   target=Path(d)/'not-created'
   for port in (0,65536):
    with self.assertRaises(ValueError):dc.initialize(target,port)
   self.assertFalse(target.exists())
 def test_broker_relative_file_layout_is_explicit_and_confined(self):
  from simulator.config import broker_file_references
  self.assertEqual(('passwd','acl'),broker_file_references({'relative_files':True,'password_file':'private/passwd','acl_file':'private/acl'}))
  with self.assertRaises(ValueError):broker_file_references({'relative_files':True,'password_file':'private/passwd','acl_file':'another/acl'})
  self.assertEqual(('legacy/passwd','legacy/acl'),broker_file_references({'password_file':'legacy/passwd','acl_file':'legacy/acl'}))
 def test_backend_resolves_repo_relative_bootstrap(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'config.json';p.write_text(json.dumps({'dev':{'SPRING_PROFILES_ACTIVE':'dev','SMARTBOX_BOOTSTRAP_FILE':'secrets/demo.json'}}))
   env=backend.local_environment(p,'dev');self.assertEqual(str(ROOT/'secrets/demo.json'),env['SMARTBOX_BOOTSTRAP_FILE'])
 def test_source_zip_build_identity_does_not_require_git(self):
  from firmware_support import source_identity
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p=root/'firmware/app.c';p.parent.mkdir();p.write_bytes(b'int app;')
   (root/'SOURCE.json').write_text(json.dumps({'format':1,'commit':'a'*40,'files':{'firmware/app.c':hashlib.sha256(p.read_bytes()).hexdigest()}}))
   self.assertEqual('',source_identity(root)['worktree'])
   p.write_bytes(b'int changed;');self.assertIn('firmware/app.c',source_identity(root)['worktree'])
   (root/'firmware/new.c').write_bytes(b'int added;');self.assertIn('firmware/new.c',source_identity(root)['worktree'])
 def test_stamp_identifies_current_and_rejects_missing_process(self):
  self.assertTrue(project.process_stamp(os.getpid()));self.assertIsNone(project.process_stamp(429496729))
 def test_bundle_archive_paths_reject_traversal(self):
  code=(ROOT/'tools/sdk_bundle_remote.py').read_text().split("if payload['action']=='inspect':")[0]
  scope={};exec(compile(code,'bundle-worker','exec'),scope)
  for name in ('/etc/passwd','../outside','sdk/../../outside','C:/absolute','sdk\\escape'):
   with self.assertRaises(ValueError):scope['safe_name'](name)
  self.assertEqual('sdk/build.py',str(scope['safe_name']('sdk/build.py')))
 def test_bundle_install_rejects_existing_destination(self):
  code=(ROOT/'tools/sdk_bundle_remote.py').read_text().split("if payload['action']=='inspect':")[0]
  with tempfile.TemporaryDirectory() as d:
   scope={'payload':{'source':d,'destination':d}};exec(compile(code,'bundle-worker','exec'),scope)
   with self.assertRaises(ValueError):scope['install']()
 def test_remote_worker_python38_syntax(self):
  import ast
  for name in ('firmware_remote.py','sdk_bundle_remote.py','sync_firmware.py'):
   ast.parse((ROOT/'tools'/name).read_text(),feature_version=(3,8))
 def test_sdk_install_rejects_bad_archive_before_destination_write(self):
  code=(ROOT/'tools/sdk_bundle_remote.py').read_text().split("if payload['action']=='inspect':")[0]
  for kind in ('checksum','traversal','duplicate','bad-manifest','link-escape','link-parent','privileged'):
   with self.subTest(kind=kind),tempfile.TemporaryDirectory() as d:
    source=Path(d)/'incoming';source.mkdir();target=Path(d)/'installed'
    archive=source/'sdk.tar.gz';expected={}
    with tarfile.open(archive,'w:gz') as tf:
     info=tarfile.TarInfo('sdk');info.type=tarfile.DIRTYPE;info.mode=0o755;tf.addfile(info)
     names=['sdk/file.c']
     if kind=='traversal':names=['sdk/../../escape']
     if kind=='duplicate':names*=2
     if kind=='link-parent':names=['sdk/link/file.c']
     for name in names:
      info=tarfile.TarInfo(name);data=b'int sample;';info.size=len(data);info.mode=0o4755 if kind=='privileged' else 0o644;tf.addfile(info,io.BytesIO(data))
      expected[name.removeprefix('sdk/')]={'type':'file','mode':info.mode,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()}
     if kind in ('link-escape','link-parent'):
      info=tarfile.TarInfo('sdk/link');info.type=tarfile.SYMTYPE;info.linkname='../../outside' if kind=='link-escape' else 'file.c';info.mode=0o777;tf.addfile(info)
      expected['link']={'type':'link','mode':info.mode,'target':info.linkname}
    if kind=='bad-manifest':expected={}
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    (source/'bundle.json').write_text(json.dumps({'archives':{'sdk.tar.gz':{'sha256':'0'*64 if kind=='checksum' else digest}},'manifests':{'sdk':expected}}))
    scope={'payload':{'source':str(source),'destination':str(target)}};exec(compile(code,'bundle-worker','exec'),scope)
    with self.assertRaises(ValueError):scope['install']()
    self.assertFalse(target.exists())
 def test_stop_rejects_recycled_pid_without_writing(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);directory=root/'artifacts/development/run';directory.mkdir(parents=True)
   (root/'artifacts/development-running.json').write_text(json.dumps({'pid':123,'stamp':'old','directory':str(directory),'stop_file':str(directory/'stop.request')}))
   with patch.object(project,'ROOT',root),patch.object(project,'process_stamp',return_value='different'):
    with self.assertRaises(ValueError):project.stop_status(True)
   self.assertFalse((directory/'stop.request').exists())
 def test_stop_reads_utf8_state_and_sends_only_the_owned_request(self):
  with tempfile.TemporaryDirectory(prefix='部署 空格-') as d:
   root=Path(d);directory=root/'artifacts/development/运行';directory.mkdir(parents=True)
   state=root/'artifacts/development-running.json';state.write_text(json.dumps({'pid':123,'stamp':'owned','nonce':'test-nonce','directory':str(directory),'stop_file':str(directory/'stop.request')},ensure_ascii=False),encoding='utf-8')
   with patch.object(project,'ROOT',root),patch.object(project,'process_stamp',return_value='owned'),patch.object(project.time,'sleep',side_effect=lambda _:state.unlink()):
    self.assertEqual(0,project.stop_status(True))
   self.assertEqual('test-nonce',(directory/'stop.request').read_text(encoding='utf-8'))
 def test_crash_recovery_refuses_a_live_owned_child(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);directory=root/'artifacts/development/run';directory.mkdir(parents=True)
   state=root/'artifacts/development-running.json';state.write_text(json.dumps({'pid':123,'stamp':'gone','directory':str(directory),'children':[{'pid':456,'stamp':'live'}]}))
   with patch.object(project,'ROOT',root),patch.object(project,'process_stamp',side_effect=lambda pid:None if pid==123 else 'live'):
    with self.assertRaises(ValueError):project.recover_record()
   self.assertTrue(state.exists())
 def test_browser_integration_uses_configured_local_port(self):
  import frontend_integration as integration
  from types import SimpleNamespace
  stack=integration.BrowserStack.__new__(integration.BrowserStack)
  stack.env={'SMARTBOX_DB_URL':'jdbc:mysql://127.0.0.1:13306/smartbox_test','SMARTBOX_DB_USER':'smartbox_test_app','SMARTBOX_DB_PASSWORD':'temporary-test-only'}
  with patch.object(integration.shutil,'which',return_value='mysql'),patch.object(integration.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'1')) as run:
   self.assertEqual('1',stack.sql('SELECT 1;'))
   argv=run.call_args.args[0];self.assertIn('--port=13306',argv);self.assertNotIn('temporary-test-only',' '.join(argv))
 def test_database_refuses_foreign_existing_objects_without_mutation(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);dc.initialize(root)
   from types import SimpleNamespace
   executed=[]
   def run(args,**kwargs):
    sql=kwargs['input'].decode();executed.append(sql)
    return SimpleNamespace(returncode=0,stdout=(b'12345678-1234-1234-1234-123456789abc' if 'server_uuid' in sql else b'smartbox_dev' if 'SCHEMATA' in sql else b''))
   with patch.object(dc.shutil,'which',return_value='mysql'),patch.object(dc.subprocess,'run',side_effect=run):
    with self.assertRaises(ValueError):dc.provision(root/'secrets/backend.local.json','not-a-real-password',root)
   self.assertTrue(all(sql.startswith('SELECT') for sql in executed))

if __name__=='__main__':unittest.main()
