import copy,json,ssl,sys,tempfile,unittest
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from broker_profiles import load_cloud,backend_environment,configure_client
ROOT=Path(__file__).resolve().parents[1]

class CloudConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        (self.root/'ca.crt').write_bytes((ROOT/'deploy/emqx/emqxsl-ca.crt').read_bytes())
        names={'ENV':'BOX-0001-ENV','POSE':'BOX-0001-POSE','backend':'smartbox_backend','control':'smartbox_test_control','observer':'smartbox_test_observer'}
        self.value={'format':1,'box_id':'BOX-0001','state_dir':str(self.root/'state'),'broker':{'mode':'cloud','host':'test.emqxsl.cn','port':8883,'tls':True,'ca_file':'ca.crt'},'accounts':{k:{'username':v,'password':'test-only-not-a-credential'} for k,v in names.items()}}
        self.path=self.root/'config.json'
    def tearDown(self):self.tmp.cleanup()
    def load(self):
        self.path.write_text(json.dumps(self.value),encoding='utf-8');return load_cloud(self.path,self.root)
    def test_valid_profile_needs_no_local_broker_files(self):
        c=self.load();self.assertEqual(backend_environment(c)['SMARTBOX_MQTT_URL'],'ssl://test.emqxsl.cn:8883')
        self.assertNotIn('exe',c['broker'])
    def test_no_plaintext_or_alternate_port_fallback(self):
        for field,value in [('tls',False),('port',1883),('mode','local')]:
            with self.subTest(field=field):
                prior=self.value['broker'][field];self.value['broker'][field]=value
                with self.assertRaises(ValueError):self.load()
                self.value['broker'][field]=prior
    def test_host_injection_rejected(self):
        for host in ('https://host.com','host.com/path','host.com@other.com','host.com:8883','bad..com','localhost','127.0.0.1'):
            self.value['broker']['host']=host
            with self.assertRaises(ValueError):self.load()
    def test_ca_is_required_and_parseable(self):
        (self.root/'ca.crt').write_text('not a certificate')
        with self.assertRaises(ssl.SSLError):self.load()
    def test_ca_outside_repository_rejected(self):
        self.value['broker']['ca_file']='../outside.pem'
        with self.assertRaises(ValueError):self.load()
    def test_missing_account_rejected(self):
        del self.value['accounts']['POSE']
        with self.assertRaises(ValueError):self.load()
    def test_identity_not_inferred_from_host(self):
        self.value['accounts']['ENV']['username']='other'
        with self.assertRaises(ValueError):self.load()
    def test_tls_context_requires_hostname_and_certificates(self):
        class Client:
            def tls_set_context(self,ctx):self.ctx=ctx
        client=Client();configure_client(client,self.load())
        self.assertTrue(client.ctx.check_hostname);self.assertEqual(client.ctx.verify_mode,ssl.CERT_REQUIRED)
        self.assertGreaterEqual(client.ctx.minimum_version,ssl.TLSVersion.TLSv1_2)
    def test_reserved_state_range_does_not_wrap_into_another_owner(self):
        from simulator.storage import IdentityState
        p=self.root/'BOX-0001-ENV.json';p.write_text(json.dumps({'node_id':'BOX-0001-ENV','boot_id':100,'boot_limit':101,'sentry_revision':0}),encoding='utf-8')
        state=IdentityState(self.root,'BOX-0001-ENV');self.assertEqual(101,state.reserve())
        with self.assertRaises(ValueError):state.reserve()
        self.assertEqual(101,json.loads(p.read_text())['boot_id'])
    def test_cloud_supervisor_ignores_unrelated_local_broker_port(self):
        import project,backend
        seen=[]
        def occupied(port):seen.append(port);return port==1883
        with patch('simulator.broker.port_open',side_effect=occupied),patch.object(backend,'JAR',self.root/'missing.jar'):
            with self.assertRaisesRegex(ValueError,'Build backend first'):project.serve('server',self.path,'cloud')
        self.assertEqual([8080,5173],seen)
    def test_local_supervisor_keeps_port_ownership_guard(self):
        import project
        with patch('simulator.broker.port_open',return_value=True):
            with self.assertRaisesRegex(ValueError,'Required port occupied'):project.serve('simulator',self.path,'local')
if __name__=='__main__':unittest.main()
