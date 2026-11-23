"""TASK-011 local asset configuration, initialization, server and real HTTP checks."""
from __future__ import annotations
import argparse,json,os,subprocess,sys
from pathlib import Path
from backend import ROOT,local_environment,java_executable,JAR

def seed_path(profile):return ROOT/'secrets'/('assets-'+profile+'.local.json')

def prepare(profile,config):
    env=local_environment(config,profile)
    fixture=json.loads(Path(env['SMARTBOX_BOOTSTRAP_FILE']).read_text(encoding='utf-8'))
    def account(role,box):
        matches=[u for u in fixture['users'] if u['role']==role and u['box_ids']==[box]]
        if len(matches)!=1:raise ValueError('Select exactly one configured account for each demo role and box')
        return matches[0]['username']
    operator=account('LOGISTICS_OPERATOR','BOX-0001');rider=account('COURIER','BOX-0001')
    companies=[{'name':'SmartBox演示企业A','admin_username':operator,'box_ids':['BOX-0001']}]
    riders=[rider]
    if profile=='test':
        companies.append({'name':'SmartBox演示企业B','admin_username':account('LOGISTICS_OPERATOR','BOX-0002'),'box_ids':['BOX-0002']})
        riders.append(account('COURIER','BOX-0002'))
    value={'rider_usernames':riders,'enterprises':companies,'personal_boxes':[{'box_id':'BOX-DEMO-02','display_name':'个人演示箱体（无设备）','owner_username':rider}]}
    target=seed_path(profile)
    if target.exists():
        if json.loads(target.read_text(encoding='utf-8'))!=value:raise ValueError('Asset seed already exists with different content; no overwrite')
    else:
        target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('x',encoding='utf-8') as stream:json.dump(value,stream,ensure_ascii=False,indent=2)
    print('Controlled asset configuration ready; existing identities preserved.')
    return target

def environment(config,profile):
    env=local_environment(config,profile);path=seed_path(profile)
    if not path.is_file():raise ValueError('Prepare explicit local asset seed first')
    env.update(SMARTBOX_ASSET_SEED_FILE=str(path),SMARTBOX_MQTT_ENABLED='false')
    return env

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','bootstrap','run','test','smoke'])
    p.add_argument('--profile',choices=['dev','test'],default='dev');p.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json')
    p.add_argument('--allow-test-reset',action='store_true');p.add_argument('--port',type=int,default=18080);p.add_argument('--run-dir',type=Path)
    a=p.parse_args()
    try:
        if a.action=='prepare':prepare(a.profile,a.config);return 0
        env=environment(a.config,a.profile)
        if a.action=='test':
            if a.profile!='test' or not a.allow_test_reset:raise ValueError('Explicit project test reset consent required')
            env['SMARTBOX_TEST_ALLOW_RESET']='true'
            return subprocess.call(['mvn.cmd' if os.name=='nt' else 'mvn','-B','-ntp','verify','-Pit'],env=env,cwd=ROOT/'backend')
        if not JAR.is_file():raise ValueError('Build application first')
        if a.action=='smoke':
            if not a.run_dir:raise ValueError('New artifact directory required')
            from assets_smoke import execute
            return execute(env,a.profile,a.port,a.run_dir)
        if a.action=='bootstrap':
            command=[java_executable(env),'-jar',str(JAR),'--smartbox.assets.bootstrap=true']
        else:
            from simulator.broker import port_open
            if not 1024<=a.port<=65535 or a.port in (1883,8080,5173) or port_open(a.port):raise ValueError('Choose a free isolated HTTP port')
            env.update(SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(a.port));command=[java_executable(env),'-jar',str(JAR)]
        return subprocess.call(command,env=env,cwd=ROOT/'backend')
    except (ValueError,KeyError,OSError,AssertionError,subprocess.SubprocessError):
        print('Asset action failed; check local configuration and private logs. No credentials displayed.',file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
