"""Local backend commands. Secrets are loaded from ignored configuration, never CLI passwords."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
JAR=ROOT/'backend/target/smartbox-backend-0.1.0.jar'

def java_executable(env):
    if os.name=='nt':
        home=env.get('JAVA_HOME')
        if not home:raise ValueError('JAVA_HOME required; do not use the Oracle launcher shim')
        java=Path(home)/'bin/java.exe'
        if not java.is_file():raise ValueError('JAVA_HOME executable missing')
        return str(java)
    return 'java'

def local_environment(config,profile):
    value=json.loads(config.read_text(encoding='utf-8'))[profile]
    if value.get('SPRING_PROFILES_ACTIVE')!=profile:raise ValueError('Local profile mismatch')
    env=os.environ.copy();env.update(value)
    # Local paths are repository-relative, independent of the invoking directory.
    if value.get('SMARTBOX_BOOTSTRAP_FILE'):
        bootstrap=Path(value['SMARTBOX_BOOTSTRAP_FILE'])
        env['SMARTBOX_BOOTSTRAP_FILE']=str(bootstrap if bootstrap.is_absolute() else ROOT/bootstrap)
    # A previous shell must not silently leave destructive test consent enabled.
    env.pop('SMARTBOX_TEST_ALLOW_RESET',None)
    if os.name=='nt' and value.get('SMARTBOX_WINDOWS_TCP_SELECTOR')=='true':
        # This host cannot connect AF_UNIX. A regular-file directory makes the
        # JDK's private selector pipe take its built-in TCP fallback on bind.
        marker=ROOT/'artifacts/backend/selector-tcp.marker'
        marker.parent.mkdir(parents=True,exist_ok=True)
        if not marker.exists():marker.write_text('Not a directory: process-local JDK TCP fallback.\n',encoding='utf-8')
        if not marker.is_file() or marker.is_symlink():raise ValueError('Unsafe selector marker')
        option='-Djdk.net.unixdomain.tmpdir="'+str(marker)+'"'
        env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' '+option).strip()
    return env

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['unit','build','test','bootstrap','run','smoke'])
    p.add_argument('--profile',choices=['dev','test'],default='dev')
    p.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json')
    p.add_argument('--allow-test-reset',action='store_true')
    p.add_argument('--report-dir',type=Path,default=ROOT/'artifacts/backend/TASK-003')
    p.add_argument('--mqtt-config',type=Path,help='Ignored project MQTT config; only for run')
    p.add_argument('--hardware-mqtt',action='store_true',help='Explicit TASK-007 LAN broker configuration; backend still binds loopback')
    args=p.parse_args()
    try:
        if args.hardware_mqtt and (args.action!='run' or not args.mqtt_config):raise ValueError('Hardware MQTT requires run and explicit config')
        if args.action=='unit':command=['mvn.cmd' if os.name=='nt' else 'mvn','-B','-ntp','test'];env=os.environ.copy()
        else:
            env=local_environment(args.config,args.profile)
            if args.mqtt_config:
                if args.action!='run':raise ValueError('MQTT configuration is only for run')
                from simulator.config import load
                mqtt=load(args.mqtt_config,allow_hardware=args.hardware_mqtt);account=mqtt['accounts']['backend']
                env.update({'SMARTBOX_MQTT_ENABLED':'true','SMARTBOX_MQTT_URL':'tcp://127.0.0.1:1883','SMARTBOX_MQTT_USER':account['username'],'SMARTBOX_MQTT_PASSWORD':account['password']})
            if args.action in ['build','test']:
                if args.action=='test':
                    if args.profile!='test' or not args.allow_test_reset:raise ValueError('Integration requires test profile and --allow-test-reset')
                    env['SMARTBOX_TEST_ALLOW_RESET']='true'
                command=['mvn.cmd' if os.name=='nt' else 'mvn','-B','-ntp','verify']+(['-Pit'] if args.action=='test' else [])
            elif args.action in ['bootstrap','run']:
                if not JAR.is_file():raise ValueError('Build the application first')
                command=[java_executable(env),'-jar',str(JAR)]+(['--smartbox.bootstrap=true'] if args.action=='bootstrap' else [])
            else:
                from backend_smoke import check
                return check(env,args.report_dir,java_executable(env))
        return subprocess.run(command,cwd=ROOT/'backend',env=env).returncode
    except (KeyError,ValueError,OSError) as error:
        print('Backend command rejected: '+type(error).__name__+'; verify local configuration (no credentials displayed).',file=sys.stderr);return 1
if __name__=='__main__':sys.exit(main())
