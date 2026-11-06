"""Host-only checks; every state/config mutation is confined to a temporary directory."""
import copy
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from simulator.config import prepare, load, confined
from simulator.storage import IdentityState, FileLock, atomic_json, U32
from simulator.model import Model, Exhausted
from simulator.protocol import Protocol, Rejected, pack, unpack
from simulator.broker import Broker

class Clock:
    def __init__(self):self.value=0
    def mono(self):return self.value
    def utc(self):return 1800000000000+int(self.value*1000)

class ModelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.protocol=Protocol()
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.dir=Path(self.tmp.name);self.clock=Clock();self.node='BOX-0001-ENV'
        atomic_json(self.dir/(self.node+'.json'),{'node_id':self.node,'boot_id':0,'sentry_revision':0})
        self.identity=IdentityState(self.dir,self.node)
        self.model=Model('ENV',self.identity,monotonic=self.clock.mono,utc=self.clock.utc)
    def command(self,kind='output',params=None,caps=2,key='r1'):
        return {'v':1,'box_id':'BOX-0001','node_id':self.node,'request_id':key,'operation_id':'o1',
                'issued_at':self.clock.utc(),'expires_at':self.clock.utc()+15000,'cmd':kind,'target_caps':caps,
                'params':params if params is not None else {'kind':'light','on':True,'duration_s':1}}
    def validate(self,data,suffix='cmd',**kwargs):
        return self.protocol.validate('box/BOX-0001/'+self.node+'/'+suffix,pack(data),1,
            identity={'box_id':'BOX-0001','node_id':self.node},caps=self.model.caps,**kwargs)
    def test_sentry_duration_starts_at_actual_armed(self):
        c=self.command('sentry',{'on':True,'duration_s':2,'sentry_revision':1},64)
        self.assertIsNone(self.model.request(self.validate(c)))
        self.clock.value=2.999;self.assertEqual([],self.model.tick());self.assertEqual('ARMING',self.model.sentry)
        self.clock.value=3.1;self.assertEqual('ok',self.model.tick()[0]['result'])
        self.clock.value=5.09;self.model.tick();self.assertEqual('ARMED',self.model.sentry)
        self.clock.value=5.1;self.model.tick();self.assertEqual('DISARMED',self.model.sentry)
    def test_sentry_zero_has_no_expiry(self):
        self.model.request(self.command('sentry',{'on':True,'duration_s':0,'sentry_revision':1},64))
        self.clock.value=3;self.model.tick();self.clock.value=999999;self.model.tick()
        self.assertEqual('ARMED',self.model.sentry)
    def test_duplicate_in_progress_does_not_restart_buffer(self):
        c=self.command('sentry',{'on':True,'duration_s':0,'sentry_revision':1},64)
        self.model.request(c);self.clock.value=2;self.assertIsNone(self.model.request(c))
        self.clock.value=3;ack=self.model.tick()[0];self.assertEqual(ack,self.model.request(c));self.assertEqual(1,self.model.actions)
    def test_duplicate_conflict_and_32_item_cache(self):
        c=self.command();ack=self.model.request(c);self.assertEqual(ack,self.model.request(c));self.assertEqual(1,self.model.actions)
        other=copy.deepcopy(c);other['params']['on']=False
        self.assertEqual('conflicting_request',self.model.request(other)['code']);self.assertTrue(self.model.outputs['light'])
        for n in range(40):self.model.request(self.command(key='r'+str(n+2)))
        self.assertEqual(32,len(self.model.cache))
    def test_output_uses_monotonic_deadline(self):
        self.model.request(self.command());self.clock.value=.999;self.model.tick();self.assertTrue(self.model.outputs['light'])
        self.clock.value=1;self.model.tick();self.assertFalse(self.model.outputs['light'])
    def test_profile_and_gps_mock_actions(self):
        p={'profile_id':'P','temp_min_c':0,'temp_max_c':30,'temp_hysteresis_c':1,'humi_min_pct':0,'humi_max_pct':90,'humi_hysteresis_pct':1}
        result=self.model.request(self.validate(self.command('profile',p,1)))
        self.assertEqual('P',result['actual_state']['profile_id'])
        node='BOX-0001-POSE';atomic_json(self.dir/(node+'.json'),{'node_id':node,'boot_id':0,'sentry_revision':0})
        m=Model('POSE',IdentityState(self.dir,node),seed=7,monotonic=self.clock.mono,utc=self.clock.utc)
        c=self.command('gps_mode',{'mode':'WALK','seed':7},32);c['node_id']=node
        self.assertEqual('WALK',m.request(c)['actual_state']['mode'])
        c=self.command('calibrate',{},8,key='cal');c['node_id']=node
        self.assertTrue(m.request(c)['actual_state']['calibrated']);self.assertEqual('MOCK',m.property()['loc']['src'])
    def test_invalid_and_unsynced_samples(self):
        self.model.invalid=True;self.model.synced=False
        p=self.model.property();self.assertIsNone(p['ts']);self.assertFalse(p['env']['valid']);self.assertIsNone(p['env']['temp_c'])
        self.protocol.validate('box/BOX-0001/'+self.node+'/property',pack(p),0)
    def test_deterministic_input_sequence(self):
        atomic_json(self.dir/(self.node+'.json'),{'node_id':self.node,'boot_id':5,'sentry_revision':0})
        other=Model('ENV',IdentityState(self.dir,self.node),seed=1,monotonic=self.clock.mono,utc=self.clock.utc)
        self.assertEqual([self.model.property()['env'] for _ in range(3)],[other.property()['env'] for _ in range(3)])
    def test_event_capacity_and_exact_ack(self):
        for _ in range(70):self.model.event()
        self.assertEqual(64,len(self.model.pending));self.assertEqual(6,self.model.overflow)
        self.assertEqual([{'boot_id':1,'first_seq':1,'last_seq':6}],self.model.lost)
        self.model.acknowledged({'boot_id':1,'event_seqs':[7,9,999]})
        self.assertIn(8,self.model.pending);self.assertNotIn(7,self.model.pending)
        self.assertEqual('DEGRADED',self.model.heartbeat()['storage'])
    def test_wrong_boot_confirmation_and_original_time(self):
        e=self.model.event();self.clock.value=10
        with self.assertRaises(Rejected):self.model.acknowledged({'boot_id':2,'event_seqs':[1]})
        batch=list(self.model.batches())[0];self.assertEqual(e['ts'],batch['events'][0]['ts'])
        self.model.sent.add(1);again=list(self.model.batches())[0]
        self.assertTrue(again['events'][0]['replayed']);self.assertEqual(e['uptime_ms'],again['events'][0]['uptime_ms'])
    def test_batches_respect_count_and_bytes(self):
        for _ in range(20):self.model.event()
        for batch in self.model.batches():
            self.assertLessEqual(len(batch['events']),4);self.assertLessEqual(len(pack(batch)),1536)
            self.validate(batch,'event')
    def test_sequence_exhaustion_does_not_wrap(self):
        self.model.seq=U32;self.model.property_seq=U32
        with self.assertRaises(Exhausted):self.model.event()
        with self.assertRaises(Exhausted):self.model.property()
    def test_identity_save_failure_does_not_reserve_generation(self):
        before=self.identity.value.copy()
        with patch('simulator.storage.atomic_json',side_effect=OSError('injected')):
            with self.assertRaises(OSError):self.identity.reserve()
        self.assertEqual(before,self.identity.value)
    def test_revision_save_failure_does_not_change_action(self):
        with patch.object(self.identity,'revision',side_effect=OSError('injected')):
            ack=self.model.request(self.command('sentry',{'on':True,'duration_s':1,'sentry_revision':1},64))
        self.assertEqual('storage_error',ack['code']);self.assertEqual('DISARMED',self.model.sentry);self.assertEqual(0,self.model.actions)
    def test_restart_generation_revision_and_degraded_limit(self):
        self.identity.revision(5)
        next_model=Model('ENV',IdentityState(self.dir,self.node))
        self.assertEqual(2,next_model.boot);self.assertEqual(5,next_model.revision);self.assertEqual('DEGRADED',next_model.sentry)
        self.assertEqual('stale_revision',next_model.request(self.command('sentry',{'on':True,'duration_s':1,'sentry_revision':5},64))['code'])
    def test_bad_or_exhausted_state_and_duplicate_lock(self):
        atomic_json(self.identity.path,{'node_id':self.node,'boot_id':U32,'sentry_revision':0})
        with self.assertRaises(ValueError):IdentityState(self.dir,self.node).reserve()
        atomic_json(self.identity.path,{'node_id':'WRONG','boot_id':0,'sentry_revision':0})
        with self.assertRaises(ValueError):IdentityState(self.dir,self.node)
        lock=self.dir/'lock'
        with FileLock(lock):
            with self.assertRaises(ValueError):
                with FileLock(lock):pass
    def test_invalid_json_is_rejected(self):
        for raw in [b'{',b'{"v":1,"v":2}',b'{"v":NaN}',b'{"v":1e999}',b'\xff',b'[]']:
            with self.subTest(raw=raw),self.assertRaises(Rejected):unpack(raw)
    def test_version_caps_expiry_and_retained(self):
        cases=[];c=self.command();bad=copy.deepcopy(c);bad['v']=2;cases.append((bad,{}))
        bad=copy.deepcopy(c);bad['target_caps']=16;cases.append((bad,{}))
        cases.extend([(c,{'now':c['expires_at']}),(c,{'synced':False}),(c,{'retained':True})])
        for data,kw in cases:
            retained=kw.pop('retained',False)
            with self.subTest(data=data,kw=kw),self.assertRaises(Rejected):
                self.protocol.validate('box/BOX-0001/'+self.node+'/cmd',pack(data),1,retained,caps=71,**kw)
    def test_payload_topic_and_schema_boundaries(self):
        c=self.command()
        for size in (1536,1537):
            x=copy.deepcopy(c);x['extra']='x'*(size-len(pack(c))-11)
            # Compute padding from the actual JSON, including UTF-8 bytes.
            x['extra']='x'*(size-len(pack({**c,'extra':''})))
            self.assertEqual(size,len(pack(x)))
            if size==1536:self.validate(x)
            else:
                with self.assertRaises(Rejected):self.validate(x)
        with self.assertRaises(Rejected):self.protocol.validate('x'*129,pack(c))
    def test_protocol_identity_and_profile_recovery_interval(self):
        c=self.command();c['node_id']='WRONG'
        with self.assertRaises(Rejected):self.validate(c)
        p={'profile_id':'P','temp_min_c':0,'temp_max_c':1,'temp_hysteresis_c':1,'humi_min_pct':0,'humi_max_pct':90,'humi_hysteresis_pct':1}
        with self.assertRaises(Rejected):self.validate(self.command('profile',p,1))
    def test_receive_queue_full_is_observable(self):
        from simulator.runtime import Node
        config={'accounts':{'ENV':{'username':self.node,'password':'test-only'}}}
        node=Node(config,'ENV',self.dir)
        from types import SimpleNamespace
        m=SimpleNamespace(topic='test',payload=b'{}',qos=1,retain=False)
        for _ in range(33):node.receive(None,None,m)
        self.assertEqual(32,len(node.received));self.assertEqual(1,node.drops)

    def test_frame_and_topic_semantic_boundaries(self):
        from check_contracts import semantics
        c=self.command();ctx={'topic':'box/BOX-0001/'+self.node+'/cmd','qos':1,'frame_bytes':2048}
        self.assertFalse(any(e.code=='frame_bytes' for e in semantics(c,'Command','mqtt',ctx)))
        ctx['frame_bytes']=2049
        self.assertTrue(any(e.code=='frame_bytes' for e in semantics(c,'Command','mqtt',ctx)))
        for length in (128,129):
            ctx={'topic':'x'*length,'qos':1}
            self.assertEqual(length==129,any(e.code=='topic_bytes' for e in semantics(c,'Command','mqtt',ctx)))
    def test_more_than_four_events_is_rejected(self):
        for _ in range(5):self.model.event()
        batch={**self.model.up(),'events':list(self.model.pending.values())}
        with self.assertRaises(Rejected):self.validate(batch,'event')
    def test_disconnect_keeps_local_deadline(self):
        self.model.request(self.command('sentry',{'on':True,'duration_s':2,'sentry_revision':1},64))
        self.clock.value=3;self.model.tick()
        # No network calls or confirmations are required for local expiry.
        self.clock.value=5;self.model.tick();self.assertEqual('DISARMED',self.model.sentry)
    def test_box_state_is_received_without_invented_suppression(self):
        data={'v':1,'box_id':'BOX-0001','node_id':self.node,'door_state':'OPEN',
              'conclusion_id':'test-conclusion','historical':False,'ts':self.clock.utc()}
        self.validate(data,'box_state')
        self.assertEqual({},self.model.output_due)

    def test_atomic_snapshot_retries_transient_windows_reader_conflict(self):
        target=self.dir/'snapshot.json';atomic_json(target,{'version':1})
        original=os.replace;calls=[]
        def replace(source,destination):
            calls.append(1)
            if len(calls)==1:raise PermissionError('injected reader conflict')
            return original(source,destination)
        with patch('simulator.storage.os.replace',side_effect=replace),patch('simulator.storage.time.sleep'):
            atomic_json(target,{'version':2})
        self.assertEqual({'version':2},json.loads(target.read_text()));self.assertEqual(2,len(calls))
    def test_permanent_replace_failure_preserves_old_snapshot(self):
        target=self.dir/'snapshot.json';atomic_json(target,{'version':1})
        with patch('simulator.storage.os.replace',side_effect=PermissionError('injected')),patch('simulator.storage.time.sleep'):
            with self.assertRaises(PermissionError):atomic_json(target,{'version':2})
        self.assertEqual({'version':1},json.loads(target.read_text()));self.assertEqual([],list(self.dir.glob('*.tmp')))

    def test_cleanup_error_is_not_silently_treated_as_success(self):
        from simulator.scenarios import Run
        runner=object.__new__(Run)
        runner.nodes={'ENV':{}};runner.records=[];runner.cleanup_errors=[]
        def failure(role):
            runner.nodes.pop(role)
            raise AssertionError('graceful exit failed')
        runner.stop_node=failure
        runner.safe_nodes_stopped()
        self.assertEqual(['AssertionError'],runner.cleanup_errors)

    def test_partial_capabilities_do_not_satisfy_requested_mask(self):
        from check_contracts import semantics
        c=self.command();c['target_caps']=3
        issues=semantics(c,'Command','mqtt',{'caps':1})
        self.assertTrue(any(e.code=='caps' for e in issues))

    def test_json_key_order_does_not_change_request_identity(self):
        original=self.command();ack=self.model.request(original)
        reordered=copy.deepcopy(original)
        reordered['params']=dict(reversed(list(original['params'].items())))
        self.assertEqual(ack,self.model.request(reordered));self.assertEqual(1,self.model.actions)

class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        (self.root/'deploy/mosquitto').mkdir(parents=True)
        shutil.copy2(ROOT/'deploy/mosquitto/mosquitto.conf.template',self.root/'deploy/mosquitto/mosquitto.conf.template')
        self.exe=self.root/'mosquitto.exe';self.exe.touch();self.exe.with_name('mosquitto_passwd.exe' if os.name=='nt' else 'mosquitto_passwd').touch()
        self.path=self.root/'secrets/mqtt.local.json'
    def convert(self,args,**kwargs):
        from types import SimpleNamespace
        Path(args[-1]).write_text('hashed-fixture-only',encoding='utf-8')
        self.assertNotIn('-b',args);return SimpleNamespace(returncode=0)
    def test_prepare_and_repeat_preserve_credentials_state(self):
        with patch('simulator.config.subprocess.run',side_effect=self.convert):
            c=prepare(self.path,self.root,self.exe)
        before=self.path.read_bytes();state=IdentityState(c['state_dir'],'BOX-0001-ENV');state.reserve()
        prepare(self.path,self.root,self.exe)
        self.assertEqual(before,self.path.read_bytes());self.assertEqual(1,IdentityState(c['state_dir'],'BOX-0001-ENV').value['boot_id'])
        self.assertNotIn('#',Path(c['broker']['acl_file']).read_text())
    def test_failed_password_conversion_removes_plain_temp(self):
        from types import SimpleNamespace
        with patch('simulator.config.subprocess.run',return_value=SimpleNamespace(returncode=1)):
            with self.assertRaises(ValueError):prepare(self.path,self.root,self.exe)
        self.assertEqual([],list(self.root.rglob('passwd.tmp')));self.assertFalse(self.path.exists())
        with self.assertRaises(ValueError):prepare(self.path,self.root,self.exe)
    def test_paths_and_missing_config_rejected(self):
        with self.assertRaises(ValueError):confined(self.root/'..'/'outside',self.root)
        with self.assertRaises(ValueError):confined(self.root.parent/'outside',self.root)
        with self.assertRaises(OSError):load(self.path,self.root)
    def test_missing_old_state_is_not_recreated(self):
        with patch('simulator.config.subprocess.run',side_effect=self.convert):c=prepare(self.path,self.root,self.exe)
        (Path(c['state_dir'])/'BOX-0001-ENV.json').unlink()
        with self.assertRaises(OSError):prepare(self.path,self.root,self.exe)
    def test_port_occupied_does_not_start_or_stop_process(self):
        with patch('simulator.broker.port_open',return_value=True),patch('simulator.broker.subprocess.Popen') as popen:
            with self.assertRaises(ValueError):Broker({},self.root).start()
            popen.assert_not_called()

    def test_linked_config_directory_is_rejected(self):
        target=self.root/'real';target.mkdir();link=self.root/'link'
        try:link.symlink_to(target,target_is_directory=True)
        except OSError:
            # Windows may not allow symlink creation; mock the same reparse property.
            with patch.object(Path,'is_symlink',return_value=True):
                with self.assertRaises(ValueError):confined(self.path,self.root)
        else:
            with self.assertRaises(ValueError):confined(link/'config.json',self.root)
    def test_nonlocal_endpoint_is_rejected(self):
        with patch('simulator.config.subprocess.run',side_effect=self.convert):c=prepare(self.path,self.root,self.exe)
        c['broker']['host']='0.0.0.0';atomic_json(self.path,c)
        with self.assertRaises(ValueError):load(self.path,self.root)
