"""Project-only config and credential preparation. Never pass passwords as argv."""
from __future__ import annotations
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import ipaddress
import socket
from .storage import atomic_json, FileLock, IdentityState

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / 'secrets/mqtt.local.json'
UP = ('property','heartbeat','event','command_ack','availability')
DOWN = ('cmd','event_ack','box_state')
ROLES = {'ENV':71, 'POSE':120}
CREATE_FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)

def confined(path, root):
    path = Path(path).absolute()
    if any(part in ('.','..') or '\n' in part or '\r' in part for part in path.parts):
        raise ValueError('Unsafe path')
    for current in (path, *path.parents):
        if current.is_symlink() or (current.exists() and getattr(current.lstat(), 'st_file_attributes', 0) & 0x400):
            raise ValueError('Linked path rejected')
    # Windows TEMP may contain an 8.3 name; compare canonical paths on both sides.
    path = path.resolve(); root = Path(root).resolve()
    try: path.relative_to(root)
    except ValueError: raise ValueError('Path outside project') from None
    return path

def load(path=DEFAULT, root=ROOT, allow_hardware=False):
    path = confined(path, root)
    value = json.loads(path.read_text(encoding='utf-8'))
    if value.get('format') != 1 or value.get('box_id') != 'BOX-0001': raise ValueError('Invalid config')
    b = value['broker']
    if b['host'] != '127.0.0.1' or b['port'] != 1883: raise ValueError('Local endpoint required')
    hardware=b.get('mode')=='hardware'
    if b.get('mode') not in (None,'local','hardware'):raise ValueError('Unknown broker mode')
    if hardware:
        if not allow_hardware:raise ValueError('Hardware broker requires explicit hardware entry')
        address=ipaddress.IPv4Address(b['bind_ipv4']);subnet=ipaddress.IPv4Network(b['allowed_subnet'],strict=True)
        if not address.is_private or address.is_loopback or address.is_link_local or address.is_multicast or address.is_unspecified or address not in subnet or subnet.prefixlen<16:raise ValueError('Unsafe hardware network')
        with socket.socket() as probe:probe.bind((str(address),0))
    for key in ('config','password_file','acl_file'):
        confined(b[key], root)
        if not Path(b[key]).is_file(): raise ValueError('Missing broker file')
    if not Path(b['exe']).is_file() or not Path(b['passwd_exe']).is_file(): raise ValueError('Missing broker tools')
    template=(Path(root)/('deploy/mosquitto/mosquitto.hardware.conf.template' if hardware else 'deploy/mosquitto/mosquitto.conf.template')).read_text(encoding='utf-8')
    expected=template.replace('PASSWORD_FILE',b['password_file']).replace('ACL_FILE',b['acl_file'])
    if hardware:expected=expected.replace('BIND_IPV4',str(address))
    if Path(b['config']).read_text(encoding='utf-8')!=expected:raise ValueError('Broker configuration drift; no overwrite')
    confined(value['state_dir'], root)
    for role in ROLES:
        account = value['accounts'][role]
        if account['username'] != value['box_id']+'-'+role or not account['password']: raise ValueError('Invalid node account')
    users = [a['username'] for a in value['accounts'].values()]
    if len(set(users)) != len(users) or any(not a['password'] for a in value['accounts'].values()): raise ValueError('Invalid accounts')
    return value

def prepare(path=DEFAULT, root=ROOT, executable=None):
    root = Path(root).resolve(); path = confined(path, root)
    path.parent.mkdir(parents=True, exist_ok=True)
    with FileLock(path.parent / (path.name+'.lock')):
        if path.exists():
            value = load(path, root)
            for role in ROLES: IdentityState(value['state_dir'], value['accounts'][role]['username'])
            return value
        base = path.parent / (path.stem+'-files')
        confined(base, root)
        if base.exists(): raise ValueError('Unknown existing preparation directory')
        exe = Path(executable or shutil.which('mosquitto') or r'C:\Program Files\mosquitto\mosquitto.exe')
        passwd = exe.with_name('mosquitto_passwd.exe' if os.name=='nt' else 'mosquitto_passwd')
        if not exe.is_file() or not passwd.is_file(): raise ValueError('Broker tools missing')
        base.mkdir()
        value = {'format':1, 'box_id':'BOX-0001', 'state_dir':str(base/'state'),
                 'broker':{'host':'127.0.0.1','port':1883,'exe':str(exe),'passwd_exe':str(passwd),
                           'config':str(base/'mosquitto.conf'),'password_file':str(base/'passwd'), 'acl_file':str(base/'acl')},
                 'accounts':{}}
        names = {'ENV':'BOX-0001-ENV','POSE':'BOX-0001-POSE','backend':'smartbox_backend',
                 'control':'smartbox_test_control','observer':'smartbox_test_observer'}
        value['accounts'] = {k:{'username':n,'password':secrets.token_urlsafe(32)} for k,n in names.items()}
        temporary = base / 'passwd.tmp'
        try:
            temporary.write_text(''.join(a['username']+':'+a['password']+'\n' for a in value['accounts'].values()),encoding='utf-8')
            result = subprocess.run([str(passwd),'-U',str(temporary)],capture_output=True,creationflags=CREATE_FLAGS)
            if result.returncode: raise ValueError('Password conversion failed')
            hashed = temporary.read_bytes()
            if any(a['password'].encode() in hashed for a in value['accounts'].values()): raise ValueError('Password file not hashed')
            os.replace(temporary, base/'passwd')
            acl = []
            for role in ROLES:
                node = names[role]; acl.append('user '+node)
                acl.extend('topic write box/BOX-0001/'+node+'/'+suffix for suffix in UP)
                acl.extend('topic read box/BOX-0001/'+node+'/'+suffix for suffix in DOWN)
            for key in ('backend','control','observer'):
                acl.append('user '+names[key])
                for role in ROLES:
                    node = names[role]
                    acl.extend('topic read box/BOX-0001/'+node+'/'+suffix for suffix in UP)
                    acl.extend('topic '+('read' if key=='observer' else 'write')+' box/BOX-0001/'+node+'/'+suffix for suffix in DOWN)
            (base/'acl').write_text('\n'.join(acl)+'\n',encoding='utf-8')
            template = (root/'deploy/mosquitto/mosquitto.conf.template').read_text(encoding='utf-8')
            template = template.replace('PASSWORD_FILE',str(base/'passwd')).replace('ACL_FILE',str(base/'acl'))
            (base/'mosquitto.conf').write_text(template,encoding='utf-8')
            (base/'state').mkdir()
            for role in ROLES:
                node=names[role];atomic_json(base/'state'/(node+'.json'),{'node_id':node,'boot_id':0,'sentry_revision':0})
            atomic_json(path,value)
            return load(path,root)
        finally:
            temporary.unlink(missing_ok=True)
