"""TASK-007 private provisioning and hardware broker. Never accepts password arguments."""
from __future__ import annotations
import argparse
import copy
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
from urllib.parse import urlsplit
from simulator.config import ROOT, load, confined
from simulator.storage import atomic_json, FileLock

DEFAULT=ROOT/'secrets/firmware.local.json'
GENERATED=ROOT/'firmware/include/sb_local_config.h'

def network(value):
    address=ipaddress.IPv4Address(value['broker_ipv4'])
    subnet=ipaddress.IPv4Network(value['allowed_subnet'],strict=True)
    if not address.is_private or address.is_loopback or address.is_link_local or address.is_multicast or address.is_unspecified or address not in subnet or subnet.prefixlen<16:raise ValueError('Unsafe hardware network')
    with socket.socket() as probe:probe.bind((str(address),0))
    return str(address),str(subnet)

def wifi(value):
    if value.get('format')!=1 or value.get('wifi_security')!='WPA2_PSK':raise ValueError('Unsupported private configuration')
    ssid=value['wifi_ssid'];password=value['wifi_password'];ntp=value['ntp_server']
    if not 1<=len(ssid.encode('utf-8'))<=32 or not 8<=len(password.encode('utf-8'))<=63:raise ValueError('Wi-Fi fields missing or invalid')
    if any(ord(c)<32 for c in ssid+password+ntp) or not ntp or len(ntp)>253:raise ValueError('Invalid private field')

def known_boots():
    """Read only project DBs using their existing runtime accounts, never root."""
    config=json.loads((ROOT/'secrets/backend.local.json').read_text(encoding='utf-8'))
    result={'ENV':0,'POSE':0};exe=shutil.which('mysql')
    if not exe:raise ValueError('Existing mysql client required for safe generation provisioning')
    for profile in ('dev','test'):
        env=config[profile];url=urlsplit(env['SMARTBOX_DB_URL'].removeprefix('jdbc:'))
        db='smartbox_'+profile
        if url.hostname not in ('127.0.0.1','localhost') or url.path!='/'+db or env['SMARTBOX_DB_USER']!=('smartbox_app' if profile=='dev' else 'smartbox_test_app'):raise ValueError('Project database identity mismatch')
        child=os.environ.copy();child['MYSQL_PWD']=env['SMARTBOX_DB_PASSWORD']
        base=[exe,'--protocol=TCP','-h127.0.0.1','-u'+env['SMARTBOX_DB_USER'],'--batch','--skip-column-names',db]
        def query(sql):
            run=subprocess.run(base,input=sql.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=child,timeout=12,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            if run.returncode:raise ValueError('Project read-only boot inspection failed; details suppressed')
            return run.stdout.decode().splitlines()
        tables=query("SELECT a.TABLE_NAME FROM information_schema.COLUMNS a JOIN information_schema.COLUMNS b ON a.TABLE_SCHEMA=b.TABLE_SCHEMA AND a.TABLE_NAME=b.TABLE_NAME WHERE a.TABLE_SCHEMA=DATABASE() AND a.COLUMN_NAME='boot_id' AND b.COLUMN_NAME='node_id';")
        for table in tables:
            if not table.replace('_','').isalnum():raise ValueError('Unexpected table identifier')
            for row in query('SELECT node_id,COALESCE(MAX(boot_id),0) FROM `'+table+"` WHERE node_id IN ('BOX-0001-ENV','BOX-0001-POSE') GROUP BY node_id;"):
                node,boot=row.split('\t');role=node.removeprefix('BOX-0001-');result[role]=max(result[role],int(boot))
    return result

def prepare(path=DEFAULT,mqtt_path=ROOT/'secrets/mqtt.local.json'):
    path=confined(path,ROOT);value=json.loads(path.read_text(encoding='utf-8'));wifi(value);address,subnet=network(value)
    mqtt=load(mqtt_path,ROOT);base=confined(ROOT/'secrets/hardware',ROOT);base.mkdir(parents=True,exist_ok=True)
    confined(GENERATED,ROOT)
    with FileLock(base/'provision.lock'):
        ledger=confined(base/'boot-reservations.json',ROOT)
        prior=json.loads(ledger.read_text(encoding='utf-8')) if ledger.exists() else {'format':1,'reserved_through':{'ENV':0,'POSE':0}}
        if prior.get('format')!=1 or set(prior['reserved_through'])!={'ENV','POSE'}:raise ValueError('Invalid generation ledger; no reset')
        if any(type(n) is not int or not 0<=n<=4294967295 for n in prior['reserved_through'].values()):raise ValueError('Invalid generation values; no reset')
        observed=known_boots();bounds={}
        for role in ('ENV','POSE'):
            state=Path(mqtt['state_dir'])/('BOX-0001-'+role+'.json')
            simulator=json.loads(state.read_text(encoding='utf-8'))['boot_id'] if state.exists() else 0
            if type(simulator) is not int or not 0<=simulator<=4294967295:raise ValueError('Invalid simulator generation; no reset')
            floor=max(prior['reserved_through'][role],observed[role],simulator)
            if not 0<=floor<=4294967295-65536:raise ValueError('Generation space exhausted')
            bounds[role]={'floor':floor,'limit':floor+65536};prior['reserved_through'][role]=floor+65536
        # Reserve first; failure after reservation wastes a range, never reuses it.
        atomic_json(ledger,prior)
        lines=['/* Generated private firmware configuration. Never commit. */','#ifndef SB_LOCAL_CONFIG_H','#define SB_LOCAL_CONFIG_H']
        def define(k,v):
            # Octal UTF-8 bytes avoid C universal-character surrogate issues and
            # ambiguous hexadecimal escapes adjacent to password characters.
            literal='"'+''.join(chr(b) if 32<=b<127 and b not in (34,92) else '\\%03o'%b for b in v.encode('utf-8'))+'"'
            lines.append('#define '+k+' '+literal)
        define('SB_WIFI_SSID',value['wifi_ssid']);define('SB_WIFI_PASSWORD',value['wifi_password']);define('SB_BROKER_IPV4',address);define('SB_NTP_SERVER',value['ntp_server'])
        for role in ('ENV','POSE'):
            lines.append('#if defined(SB_ROLE_ENV)' if role=='ENV' else '#else')
            define('SB_MQTT_USER',mqtt['accounts'][role]['username']);define('SB_MQTT_PASSWORD',mqtt['accounts'][role]['password'])
            lines.extend(['#define SB_BOOT_FLOOR '+str(bounds[role]['floor'])+'u','#define SB_BOOT_LIMIT '+str(bounds[role]['limit'])+'u'])
        lines.extend(['#endif','#endif',''])
        GENERATED.write_text('\n'.join(lines),encoding='utf-8')
        broker=copy.deepcopy(mqtt);b=broker['broker'];b.update(mode='hardware',bind_ipv4=address,allowed_subnet=subnet,config=str(base/'mosquitto.conf'))
        template=(ROOT/'deploy/mosquitto/mosquitto.hardware.conf.template').read_text(encoding='utf-8')
        Path(b['config']).write_text(template.replace('PASSWORD_FILE',b['password_file']).replace('ACL_FILE',b['acl_file']).replace('BIND_IPV4',address),encoding='utf-8')
        atomic_json(base/'mqtt.local.json',broker)
        atomic_json(base/'provisioning.json',{'format':1,'box_id':'BOX-0001','generations':bounds,'broker_ipv4':address,'allowed_subnet':subnet,'note':'Each firmware may only connect inside its allocated boot range. Keep ledger and evidence.'})
        load(base/'mqtt.local.json',ROOT,allow_hardware=True)
    print('Hardware configuration and generation ranges prepared; credentials not displayed.')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['check','prepare','broker'])
    p.add_argument('--config',type=Path,default=DEFAULT);p.add_argument('--run-dir',type=Path)
    a=p.parse_args()
    try:
        if a.action=='check':wifi(json.loads(confined(a.config,ROOT).read_text(encoding='utf-8')));print('Wi-Fi fields valid; credentials not displayed.')
        elif a.action=='prepare':prepare(a.config)
        else:
            import time
            from simulator.broker import Broker
            if not a.run_dir:raise ValueError('New evidence directory required')
            directory=confined(a.run_dir,ROOT/'artifacts');directory.mkdir(parents=True,exist_ok=False)
            with Broker(load(ROOT/'secrets/hardware/mqtt.local.json',allow_hardware=True),directory):
                print('Own hardware broker running; Ctrl+C stops only this process.',flush=True)
                while True:time.sleep(.5)
        return 0
    except KeyboardInterrupt:return 0
    except (OSError,ValueError,KeyError,subprocess.SubprocessError):print('Hardware action rejected; inspect configuration/network/dependencies. No credentials displayed.');return 1
if __name__=='__main__':raise SystemExit(main())
