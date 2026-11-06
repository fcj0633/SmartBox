"""Shared SSH transport; no credentials or host-specific defaults are committed."""
from __future__ import annotations
import base64
import json
from pathlib import Path
import shlex
import subprocess

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
