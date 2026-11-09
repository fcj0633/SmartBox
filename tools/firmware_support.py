"""Shared SSH transport; no credentials or host-specific defaults are committed."""
from __future__ import annotations
import base64
import json
from pathlib import Path
import shlex
import subprocess
import hashlib
import re

def source_identity(root):
    """A source ZIP has no .git; never accidentally use a parent repository."""
    root=Path(root)
    if (root/'.git').exists():
        return {'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
                'worktree':subprocess.check_output(['git','status','--short','--','firmware','tools','tests'],cwd=root,text=True),
                'diff':subprocess.check_output(['git','diff','--binary','HEAD','--','firmware','tools','tests'],cwd=root)}
    path=root/'SOURCE.json'
    if not path.is_file():raise ValueError('Source has neither root Git metadata nor a delivered SOURCE.json manifest')
    value=json.loads(path.read_text(encoding='utf-8'))
    if value.get('format')!=1 or not re.fullmatch(r'[0-9a-f]{40}',value.get('commit','')):raise ValueError('Invalid source delivery manifest')
    changes=[]
    for name,expected in value['files'].items():
        relative=Path(name)
        if relative.is_absolute() or relative.drive or '..' in relative.parts or '\\' in name:raise ValueError('Unsafe source manifest path')
        p=root/relative
        if p.is_symlink():raise ValueError('Linked source manifest input')
        if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=expected:changes.append(name)
    for directory in ('firmware','tools','tests'):
        for p in (root/directory).rglob('*'):
            if '__pycache__' in p.parts or not p.is_file() or p.suffix not in ('.c','.h','.gn','.gni','.py','.txt','.md') or p.name=='sb_local_config.h':continue
            name=p.relative_to(root).as_posix()
            if name not in value['files']:changes.append(name)
    return {'commit':value['commit'],'worktree':'\n'.join(changes),
            'diff':('Source archive deviations (full current source captured separately):\n'+'\n'.join(changes)).encode() if changes else b''}

def connection_arguments(parser):
    parser.add_argument("--host",required=True)
    parser.add_argument("--user",required=True)
    parser.add_argument("--key",required=True,type=Path)
    parser.add_argument("--port",type=int,default=22)

def ssh_args(args,command):
    return ["ssh","-i",str(args.key),"-p",str(args.port),"-o","IdentitiesOnly=yes",
            "-o","BatchMode=yes","-o","StrictHostKeyChecking=yes","-o","ConnectTimeout=10",
            args.user+"@"+args.host,command]

def remote_python(args,script,payload=None,check=True):
    prefix="import json,base64\npayload=json.loads(base64.b64decode("+repr(base64.b64encode(json.dumps(payload).encode()).decode())+"))\n"
    result=subprocess.run(ssh_args(args,"python3 -"),input=(prefix+script).encode(),stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    output=result.stdout.decode("utf-8",errors="replace")
    if check and result.returncode:raise RuntimeError("Remote operation failed ("+str(result.returncode)+"): "+output)
    return result.returncode,output

def remote_command(args,argv,**kwargs):
    return subprocess.run(ssh_args(args," ".join(shlex.quote(str(a)) for a in argv)),**kwargs)
