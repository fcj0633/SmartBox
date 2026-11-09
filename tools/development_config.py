"""Portable private configuration and deliberately narrow first-database provisioning."""
from __future__ import annotations
import base64
import getpass
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
from urllib.parse import urlsplit

ROOT=Path(__file__).resolve().parents[1]
def write_new(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('x',encoding='utf-8') as stream:
        json.dump(value,stream,ensure_ascii=False,indent=2);stream.write('\n')
    if os.name!='nt':path.chmod(0o600)

def initialize(root=ROOT,port=3306):
    if not 1<=port<=65535:raise ValueError('Invalid MySQL port')
    from simulator.config import confined
    from simulator.storage import FileLock
    root=Path(root).resolve();directory=confined(root/'secrets',root)
    directory.mkdir(parents=True,exist_ok=True)
    with FileLock(directory/'development-init.lock'):
        pending=directory/'development-init.pending.local.json'
        if pending.exists():
            _finish_initialization(root,json.loads(pending.read_text(encoding='utf-8')))
            return True
        return _initialize(root,port)

def _finish_initialization(root,document):
    if document.get('format')!=1 or set(document['profiles'])!={'dev','test'}:raise ValueError('Invalid initialization journal')
    profiles=document['profiles']
    payload={p:{k:v for k,v in e.items() if k!='_bootstrap'} for p,e in profiles.items()}
    outputs={'secrets/backend.local.json':payload}
    for profile,e in profiles.items():
        name='secrets/backend-'+profile+'.bootstrap.local.json'
        if e['SMARTBOX_BOOTSTRAP_FILE']!=name:raise ValueError('Unexpected initialization path')
        outputs[name]=e['_bootstrap']
    # Preflight all existing partial files before resuming any missing write.
    from simulator.config import confined
    for name,value in outputs.items():
        p=confined(root/name,root)
        if p.exists() and json.loads(p.read_text(encoding='utf-8'))!=value:raise ValueError('Initialization recovery conflict; no overwrite')
    for name,value in outputs.items():
        p=root/name
        if not p.exists():write_new(p,value)
    (root/'secrets/development-init.pending.local.json').unlink()

def _initialize(root,port):
    if not 1<=port<=65535:raise ValueError('Invalid MySQL port')
    path=root/'secrets/backend.local.json'
    if path.exists():
        value=json.loads(path.read_text(encoding='utf-8'))
        if set(value)!={'dev','test'}:raise ValueError('Existing configuration needs explicit inspection')
        for e in value.values():
            b=Path(e['SMARTBOX_BOOTSTRAP_FILE']);b=b if b.is_absolute() else root/b
            if not b.is_file():raise ValueError('Incomplete initialization; bootstrap file missing, do not regenerate secrets')
        return False
    profiles={}
    for profile in ('dev','test'):
        prefix='smartbox_' if profile=='dev' else 'smartbox_test_'
        bootstrap='secrets/backend-'+profile+'.bootstrap.local.json'
        boxes=['BOX-0001'] if profile=='dev' else ['BOX-0001','BOX-0002']
        users=[{'username':('demo_'+role.lower() if profile=='dev' else 'it_'+role.lower()+str(i)),
                'password':secrets.token_urlsafe(24),'role':role,'box_ids':[box]}
               for role in ('LOGISTICS_VIEWER','LOGISTICS_OPERATOR','DELIVERY_USER','COURIER') for i,box in enumerate(boxes,1)]
        # Refuse partial or unrelated files; never regenerate already issued credentials.
        if (root/bootstrap).exists():raise ValueError('Partial initialization; preserve existing bootstrap file')
        profiles[profile]={'SPRING_PROFILES_ACTIVE':profile,
            'SMARTBOX_DB_URL':f'jdbc:mysql://127.0.0.1:{port}/smartbox_{profile}',
            'SMARTBOX_MIGRATION_URL':f'jdbc:mysql://127.0.0.1:{port}/smartbox_{profile}',
            'SMARTBOX_DB_USER':prefix+'app','SMARTBOX_DB_PASSWORD':secrets.token_urlsafe(32),
            'SMARTBOX_MIGRATION_USER':prefix+'migrator','SMARTBOX_MIGRATION_PASSWORD':secrets.token_urlsafe(32),
            'SMARTBOX_JWT_SECRET_BASE64':base64.b64encode(secrets.token_bytes(32)).decode(),
            'SMARTBOX_BOOTSTRAP_FILE':bootstrap,
            'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':'8080',
            'SMARTBOX_ALLOWED_ORIGINS':'http://localhost:5173,http://127.0.0.1:5173'}
        profiles[profile]['_bootstrap']={'boxes':boxes,'users':users}
    # Reserve every generated secret before the first output. Recovery reuses the
    # same values and never generates replacements for a partially issued setup.
    pending={'format':1,'profiles':profiles}
    write_new(root/'secrets/development-init.pending.local.json',pending)
    _finish_initialization(root,pending)
    return True

def provision(config,admin_password,root=ROOT):
    from simulator.config import confined
    from simulator.storage import FileLock
    root=Path(root).resolve();directory=confined(root/'secrets',root)
    directory.mkdir(parents=True,exist_ok=True)
    with FileLock(directory/'database-provision.lock'):return _provision(config,admin_password,root)

def _provision(config,admin_password,root):
    exe=shutil.which('mysql')
    if not exe:raise ValueError('MySQL client not found')
    value=json.loads(Path(config).read_text(encoding='utf-8'))
    urls=[urlsplit(value[p]['SMARTBOX_DB_URL'].removeprefix('jdbc:')) for p in ('dev','test')]
    if any(u.hostname!='127.0.0.1' or not 1<=(u.port or 3306)<=65535 for u in urls) or urls[0].port!=urls[1].port:
        raise ValueError('Local project MySQL endpoint required')
    env=os.environ.copy();env['MYSQL_PWD']=admin_password
    base=[exe,'--protocol=TCP','-h127.0.0.1','-P'+str(urls[0].port or 3306),'-uroot','--batch','--skip-column-names']
    def query(sql):
        run=subprocess.run(base,input=sql.encode(),env=env,capture_output=True,timeout=30)
        if run.returncode:raise ValueError('Database provisioning failed; server details suppressed')
        return run.stdout.decode().strip()
    journal=root/'secrets/database-provision.local.json'
    server=query('SELECT @@server_uuid')
    if not re.fullmatch(r'[0-9a-fA-F-]{36}',server):raise ValueError('Cannot identify MySQL server')
    state=json.loads(journal.read_text(encoding='utf-8')) if journal.exists() else {'format':1,'server_uuid':server,'port':urls[0].port or 3306,'created':[]}
    if state.get('format')!=1 or state.get('server_uuid')!=server or state['port']!=(urls[0].port or 3306):raise ValueError('Provisioning journal endpoint mismatch')
    permitted={'database:smartbox_dev','database:smartbox_test','user:smartbox_app','user:smartbox_migrator','user:smartbox_test_app','user:smartbox_test_migrator'}
    if not isinstance(state['created'],list) or not set(state['created'])<=permitted:raise ValueError('Invalid provisioning ownership journal')
    # Reject every foreign object before creating any object. DDL is not transactional.
    actions=[]
    for profile,u in zip(('dev','test'),urls):
        e=value[profile];db='smartbox_'+profile
        if u.path!='/'+db or e['SMARTBOX_MIGRATION_URL']!=e['SMARTBOX_DB_URL']:raise ValueError('Project database mismatch')
        names=[('SMARTBOX_MIGRATION','smartbox_migrator' if profile=='dev' else 'smartbox_test_migrator'),('SMARTBOX_DB','smartbox_app' if profile=='dev' else 'smartbox_test_app')]
        actions.append(('database:'+db,query("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+db+"'"),f'CREATE DATABASE `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'))
        for prefix,user in names:
            password=e[prefix+'_PASSWORD']
            if e[prefix+'_USER']!=user or not re.fullmatch(r'[A-Za-z0-9_-]{20,128}',password):raise ValueError('Generated project credential required')
            exists=query("SELECT User FROM mysql.user WHERE User='"+user+"' AND Host='localhost'")
            actions.append(('user:'+user,exists,f"CREATE USER '{user}'@'localhost' IDENTIFIED BY '{password}'"))
        for key,exists,_ in actions:
            if exists and key not in state['created']:raise ValueError('Existing object is not owned by provisioning journal; import manually')
    for key,exists,sql in actions:
        if exists:continue
        query(sql)
        state['created'].append(key)
        temporary=journal.with_suffix('.tmp');temporary.write_text(json.dumps(state));os.replace(temporary,journal)
    for profile in ('dev','test'):
        e=value[profile];db='smartbox_'+profile
        query(f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON `{db}`.* TO '{e['SMARTBOX_MIGRATION_USER']}'@'localhost'")
        query(f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{db}`.* TO '{e['SMARTBOX_DB_USER']}'@'localhost'")
    return state
