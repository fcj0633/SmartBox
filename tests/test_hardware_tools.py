"""Hardware preparation failures use temporary directories and mocked OS boundaries."""
from pathlib import Path
import copy
import json
import os
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import hardware
from simulator.config import prepare,load
from simulator.storage import atomic_json

class HardwareToolTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        target=self.root/'deploy/mosquitto';target.mkdir(parents=True)
        for name in ('mosquitto.conf.template','mosquitto.hardware.conf.template'):shutil.copy2(ROOT/'deploy/mosquitto'/name,target/name)
        self.exe=self.root/'mosquitto.exe';self.exe.touch();self.exe.with_name('mosquitto_passwd.exe' if os.name=='nt' else 'mosquitto_passwd').touch()
        self.path=self.root/'secrets/mqtt.local.json'
        def convert(args,**kwargs):
            Path(args[-1]).write_text('hashed-fixture-only');return Mock(returncode=0)
        with patch('simulator.config.subprocess.run',side_effect=convert):self.mqtt=prepare(self.path,self.root,self.exe)
        self.value={'format':1,'wifi_ssid':'fixture-network','wifi_password':'fixture-password','wifi_security':'WPA2_PSK','broker_ipv4':'192.168.31.2','allowed_subnet':'192.168.31.0/24','ntp_server':'clock.invalid'}
        self.config=self.root/'secrets/firmware.local.json';atomic_json(self.config,self.value)
        self.header=self.root/'firmware/include/sb_local_config.h';self.header.parent.mkdir(parents=True)
    def generated(self):
        with patch.object(hardware,'ROOT',self.root),patch.object(hardware,'GENERATED',self.header),patch.object(hardware,'known_boots',return_value={'ENV':30,'POSE':90}),patch('socket.socket'):
            hardware.prepare(self.config,self.path)
        return json.loads((self.root/'secrets/hardware/provisioning.json').read_text())
    def test_wifi_empty_and_length_rejected(self):
        for field,value in [('wifi_ssid',''),('wifi_ssid','界'*11),('wifi_password','short'),('wifi_password','x'*64),('ntp_server','a\n')]:
            v=copy.deepcopy(self.value);v[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):hardware.wifi(v)
    def test_wifi_does_not_mutate_configuration(self):
        before=copy.deepcopy(self.value);hardware.wifi(self.value);self.assertEqual(before,self.value)
    def test_network_rejects_wildcard_loopback_multicast_linklocal(self):
        for address,subnet in [('0.0.0.0','0.0.0.0/24'),('127.0.0.1','127.0.0.0/24'),('224.0.0.1','224.0.0.0/24'),('169.254.0.1','169.254.0.0/24'),('192.168.31.2','192.168.32.0/24'),('192.168.31.2','0.0.0.0/0')]:
            v=copy.deepcopy(self.value);v.update(broker_ipv4=address,allowed_subnet=subnet)
            with self.subTest(address=address),self.assertRaises(ValueError):hardware.network(v)
    def test_unassigned_interface_fails(self):
        with patch('socket.socket') as sock:
            sock.return_value.__enter__.return_value.bind.side_effect=OSError('not assigned')
            with self.assertRaises(OSError):hardware.network(self.value)
    def test_prepare_preserves_existing_accounts_and_reserves_distinct_ranges(self):
        old=self.path.read_bytes();a=self.generated();b=self.generated()
        for role in ('ENV','POSE'):
            self.assertEqual(a['generations'][role]['limit'],b['generations'][role]['floor'])
        self.assertEqual(old,self.path.read_bytes());self.assertIn('SB_BOOT_LIMIT',self.header.read_text())
    def test_failure_after_reservation_never_reuses_range(self):
        first=self.generated();self.header.unlink();self.header.mkdir()
        with self.assertRaises(OSError):self.generated()
        ledger=json.loads((self.root/'secrets/hardware/boot-reservations.json').read_text())
        self.header.rmdir();last=self.generated()
        self.assertEqual(ledger['reserved_through']['ENV'],last['generations']['ENV']['floor'])
        self.assertGreater(last['generations']['ENV']['floor'],first['generations']['ENV']['limit'])
    def test_corrupt_ledger_is_not_reset(self):
        self.generated();p=self.root/'secrets/hardware/boot-reservations.json';p.write_text('{bad')
        with self.assertRaises(ValueError):self.generated()
        self.assertEqual('{bad',p.read_text())
    def test_generation_exhaustion_refuses(self):
        self.generated();p=self.root/'secrets/hardware/boot-reservations.json';atomic_json(p,{'format':1,'reserved_through':{'ENV':4294967295,'POSE':100}})
        with self.assertRaises(ValueError):self.generated()
    def test_negative_or_noninteger_ledger_is_not_repaired(self):
        self.generated();p=self.root/'secrets/hardware/boot-reservations.json'
        for bad in (-1,1.5,True,'10'):
            atomic_json(p,{'format':1,'reserved_through':{'ENV':bad,'POSE':100}})
            with self.subTest(value=bad),self.assertRaises(ValueError):self.generated()
    def test_hardware_load_needs_explicit_entry(self):
        self.generated();p=self.root/'secrets/hardware/mqtt.local.json'
        with self.assertRaisesRegex(ValueError,'explicit'):load(p,self.root)
        with patch('socket.socket'):self.assertEqual('hardware',load(p,self.root,allow_hardware=True)['broker']['mode'])
    def test_hardware_template_drift_is_not_overwritten(self):
        self.generated();p=self.root/'secrets/hardware/mqtt.local.json';config=json.loads(p.read_text());Path(config['broker']['config']).write_text('unexpected')
        with patch('socket.socket'),self.assertRaisesRegex(ValueError,'drift'):load(p,self.root,allow_hardware=True)
    def test_default_mode_still_rejects_nonlocal_host(self):
        v=copy.deepcopy(self.mqtt);v['broker']['host']='192.168.31.2';atomic_json(self.path,v)
        with self.assertRaisesRegex(ValueError,'Local endpoint'):load(self.path,self.root,allow_hardware=True)

if __name__=='__main__':unittest.main()
