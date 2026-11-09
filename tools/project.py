"""SmartBox developer entry point. Run from any directory; secrets never in argv."""
from __future__ import annotations
import argparse,datetime,getpass,json,os,shutil,socket,subprocess,sys,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def read_workspace(path):
    value=json.loads(path.read_text(encoding='utf-8'))
    if value.get('format')!=1:raise ValueError('Workspace format must be 1')
    return value

def connection(value):
    from types import SimpleNamespace
    ssh=value['ubuntu'];key=Path(ssh['key']).expanduser()
    if not key.is_absolute() or not key.is_file():raise ValueError('Existing external SSH key required')
    return SimpleNamespace(host=ssh['host'],user=ssh['user'],key=key,port=int(ssh.get('port',22)),remote_sdk=ssh['sdk'])

def invoke(script,*args):
    return subprocess.call([sys.executable,str(ROOT/'tools'/script),*map(str,args)],cwd=ROOT)

def process_stamp(pid):
    """OS process creation identity avoids sending stop signals to a recycled PID."""
    if os.name=='nt':
        import ctypes
        from ctypes import wintypes
        kernel=ctypes.WinDLL('kernel32',use_last_error=True)
        kernel.OpenProcess.restype=wintypes.HANDLE
        handle=kernel.OpenProcess(0x1000,False,pid)
        if not handle:return None
        try:
            times=[wintypes.FILETIME() for _ in range(4)]
            if not kernel.GetProcessTimes(wintypes.HANDLE(handle),*[ctypes.byref(t) for t in times]):return None
            return str((times[0].dwHighDateTime<<32)|times[0].dwLowDateTime)
        finally:kernel.CloseHandle(wintypes.HANDLE(handle))
    try:return Path('/proc/'+str(pid)+'/stat').read_text(encoding='utf-8').rsplit(')',1)[1].split()[19]
    except OSError:return None

def stop_status(stop=False):
    state=ROOT/'artifacts/development-running.json'
    if not state.exists():print('No project supervisor registered.');return 0
    from simulator.config import confined
    confined(state,ROOT/'artifacts')
    value=json.loads(state.read_text(encoding='utf-8'))
    directory=confined(Path(value['directory']),ROOT/'artifacts/development')
    if confined(Path(value['stop_file']),ROOT/'artifacts')!=directory/'stop.request':raise ValueError('Invalid stop request location')
    if not value.get('stamp'):raise ValueError('Missing process creation identity')
    if process_stamp(value['pid'])!=value['stamp']:
        raise ValueError('Stale supervisor record; inspect listed children before archiving record')
    if stop:
        Path(value['stop_file']).write_text(value['nonce'])
        deadline=time.monotonic()+30
        while state.exists() and time.monotonic()<deadline:time.sleep(.2)
        if state.exists():raise ValueError('Stop pending; no unknown process was killed')
        print('Own development processes stopped.')
    else:print(json.dumps({k:value[k] for k in ('pid','mode','directory')},indent=2))
    return 0

def recover_record():
    """Explicit crash recovery: archive a dead session record; never kill a PID."""
    from simulator.config import confined
    from simulator.broker import port_open
    state=confined(ROOT/'artifacts/development-running.json',ROOT/'artifacts')
    value=json.loads(state.read_text(encoding='utf-8'))
    directory=confined(Path(value['directory']),ROOT/'artifacts/development')
    if not value.get('stamp') or process_stamp(value['pid'])==value['stamp']:raise ValueError('Supervisor identity is missing or still alive')
    for child in value.get('children',[]):
        current=process_stamp(child['pid'])
        if current and (not child.get('stamp') or current==child['stamp']):raise ValueError('An owned or unverified child remains alive')
    if any(port_open(p) for p in (1883,8080,5173)):raise ValueError('Inspect occupied ports before recovering a session record')
    state.rename(directory/('recovered-record-'+str(time.time_ns())+'.json'))
    print('Dead session record archived; no process stopped.');return 0

def serve(mode,config):
    from backend import local_environment,java_executable,JAR
    from frontend import node_command,frontend_environment,stop_owned
    from simulator.config import load
    from simulator.broker import Broker,port_open
    if any(port_open(p) for p in (1883,8080,5173)):raise ValueError('Required port occupied; nothing stopped')
    if not JAR.is_file():raise ValueError('Build backend first')
    web=node_command('dev')
    mqtt_path=ROOT/('secrets/mqtt.local.json' if mode=='simulator' else 'secrets/hardware/mqtt.local.json')
    mqtt=load(mqtt_path,allow_hardware=mode=='hardware')
    env=local_environment(config,'dev');account=mqtt['accounts']['backend']
    if env.get('SERVER_ADDRESS','127.0.0.1')!='127.0.0.1' or env.get('SERVER_PORT','8080')!='8080':
        raise ValueError('Development supervisor requires loopback HTTP on port 8080')
    env.update(SMARTBOX_MQTT_ENABLED='true',SMARTBOX_MQTT_URL='tcp://127.0.0.1:1883',SMARTBOX_MQTT_USER=account['username'],SMARTBOX_MQTT_PASSWORD=account['password'])
    directory=ROOT/'artifacts/development'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    directory.mkdir(parents=True,exist_ok=False)
    state=ROOT/'artifacts/development-running.json';nonce=uuid.uuid4().hex
    record={'pid':os.getpid(),'stamp':process_stamp(os.getpid()),'mode':mode,'nonce':nonce,'directory':str(directory),'stop_file':str(directory/'stop.request'),'children':[]}
    if not record['stamp']:raise ValueError('Cannot verify own process identity')
    # The exclusive record prevents two supervisors racing between port checks.
    with state.open('x') as stream:json.dump(record,stream)
    children=[];logs=[]
    def launch(name,command,environment=None,cwd=ROOT):
        log=(directory/(name+'.log')).open('wb');logs.append(log)
        p=subprocess.Popen(command,cwd=cwd,env=environment,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0));children.append(p)
        record['children'].append({'name':name,'pid':p.pid,'stamp':process_stamp(p.pid)})
        from simulator.storage import atomic_json
        atomic_json(state,record)
        return p
    broker=Broker(mqtt,directory/'broker')
    try:
        broker.start()
        launch('backend',[java_executable(env),'-jar',str(JAR)],env,ROOT/'backend')
        deadline=time.monotonic()+90
        while not port_open(8080):
            if any(p.poll() is not None for p in children):raise ValueError('Backend exited; inspect private log')
            if time.monotonic()>deadline:raise ValueError('Backend startup timed out')
            time.sleep(.3)
        if mode=='simulator':
            for role in ('ENV','POSE'):
                launch(role,[sys.executable,str(ROOT/'tools/mqtt.py'),'node','--role',role,'--config',str(mqtt_path),'--run-dir',str(directory/role),'--stop-file',str(directory/'nodes.stop')])
        launch('frontend',web+['--host','127.0.0.1','--port','5173','--strictPort'],frontend_environment(),ROOT/'frontend')
        print('Starting http://127.0.0.1:5173 ; Ctrl+C or project.py stop ends only this session.',flush=True)
        while True:
            stop=Path(record['stop_file'])
            if stop.exists() and stop.read_text(encoding='utf-8')==nonce:break
            if any(p.poll() is not None for p in children):raise ValueError('Owned child exited; inspect private logs')
            time.sleep(.3)
    except KeyboardInterrupt:pass
    finally:
        (directory/'nodes.stop').touch()
        # Keep the broker alive until nodes have published their normal offline.
        deadline=time.monotonic()+3
        while mode=='simulator' and len(children)>1 and any(p.poll() is None for p in children[1:-1]) and time.monotonic()<deadline:time.sleep(.1)
        errors=[]
        for child in reversed(children):
            try:stop_owned(child)
            except (OSError,subprocess.SubprocessError) as error:errors.append(type(error).__name__)
        try:broker.stop()
        except (OSError,subprocess.SubprocessError) as error:errors.append(type(error).__name__)
        for log in logs:log.close()
        if errors or any(child.poll() is None for child in children):raise ValueError('Cleanup incomplete; process record retained')
        state.unlink()
    if any(port_open(p) for p in (1883,8080,5173)):raise ValueError('Port remains occupied; inspect ownership manually')
    return 0

def doctor(workspace):
    rows={name:bool(shutil.which(name)) for name in ('git','java','mvn.cmd' if os.name=='nt' else 'mvn','node','npm.cmd' if os.name=='nt' else 'npm','mysql','ssh')}
    rows['python']=sys.version.split()[0]
    rows['backend_config']=(ROOT/'secrets/backend.local.json').is_file()
    rows['mqtt_config']=(ROOT/'secrets/mqtt.local.json').is_file()
    rows['workspace_config']=workspace.is_file()
    from simulator.broker import port_open
    rows['occupied_ports']=[p for p in (1883,8080,5173) if port_open(p)]
    if workspace.exists():
        from firmware_support import remote_python
        a=connection(read_workspace(workspace))
        _,out=remote_python(a,"import os,sys,json\nfrom pathlib import Path\nprint(json.dumps({'python':sys.version.split()[0],'sdk':(Path(payload['sdk'])/'build.py').is_file(),'free_bytes':__import__('shutil').disk_usage(str(Path.home())).free}))",{'sdk':a.remote_sdk})
        rows['ubuntu']=json.loads(out.strip().splitlines()[-1])
    ready=all(rows[k] for k in rows if isinstance(rows[k],bool)) and rows.get('ubuntu',{}).get('sdk',False)
    print(json.dumps(rows,indent=2));return 0 if ready else 1

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['doctor','init','db-init','install-deps','sdk-install','firmware-build','build','start','status','stop','recover-state','check','package'])
    p.add_argument('--workspace',type=Path,default=ROOT/'secrets/workspace.local.json')
    p.add_argument('--config',type=Path,default=ROOT/'secrets/backend.local.json')
    p.add_argument('--mysql-port',type=int,default=3306)
    p.add_argument('--mode',choices=['simulator','hardware'],default='simulator')
    p.add_argument('--bundle',type=Path);p.add_argument('--destination')
    a=p.parse_args()
    try:
        if a.action=='doctor':return doctor(a.workspace)
        if a.action=='init':
            from development_config import initialize,write_new
            initialize(ROOT,a.mysql_port)
            if not a.workspace.exists():write_new(a.workspace,{'format':1,'ubuntu':{'host':'CHANGE_ME','user':'bearpi','port':22,'key':str(Path.home()/'.ssh/id_ed25519_smartbox'),'sdk':'/home/bearpi/smartbox-sdk/sdk','tool_paths':['/home/bearpi/smartbox-sdk/toolchains/gn','/home/bearpi/smartbox-sdk/toolchains/ninja','/home/bearpi/smartbox-sdk/toolchains/gcc_riscv32/bin']}})
            from simulator.config import prepare
            prepare();print('Local configuration prepared; existing secrets retained. Edit workspace before Ubuntu operations.');return 0
        if a.action=='db-init':
            from development_config import provision
            provision(a.config,getpass.getpass('Local MySQL root password (not saved): '));print('Project databases and restricted accounts provisioned. Build, then backend.py bootstrap.');return 0
        if a.action=='install-deps':
            if Path(sys.prefix).resolve()!= (ROOT/'.venv').resolve():raise ValueError('Use project .venv Python')
            for lock in ('requirements-contracts.txt','requirements-simulator.txt'):
                result=subprocess.call([sys.executable,'-m','pip','install','-r',str(ROOT/'tools'/lock)],cwd=ROOT)
                if result:return result
            return invoke('frontend.py','install')
        if a.action=='build':return invoke('backend.py','build','--config',a.config) or invoke('frontend.py','build')
        if a.action in ('status','stop'):return stop_status(a.action=='stop')
        if a.action=='recover-state':return recover_record()
        if a.action=='start':return serve(a.mode,a.config)
        if a.action=='firmware-build':
            value=read_workspace(a.workspace);c=connection(value)
            command=['--transport','ssh','--host',c.host,'--user',c.user,'--port',c.port,'--key',c.key,'--remote-sdk',c.remote_sdk]
            for item in value['ubuntu'].get('tool_paths',[]):command+=['--tool-path',item]
            return invoke('build_firmware.py',*command)
        if a.action in ('sdk-install','package'):
            if not a.bundle:raise ValueError('--bundle required')
            command=['install' if a.action=='sdk-install' else 'source','--workspace',a.workspace,'--bundle',a.bundle]
            if a.destination:command+=['--destination',a.destination]
            return invoke('sdk_bundle.py',*command)
        if a.action=='check':
            for command in (['-m','unittest','discover','-s','tests','-p','test_deployment.py','-v'],['tools/check_docs.py'],['tools/effective_contracts.py','--check'],['tools/check_contracts.py']):
                result=subprocess.call([sys.executable,*command],cwd=ROOT)
                if result:return result
            return 0
    except (ValueError,OSError,KeyError,RuntimeError,subprocess.SubprocessError) as error:
        print('Project action failed: '+type(error).__name__+'. Check prerequisites/private evidence; no credentials displayed.',file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
