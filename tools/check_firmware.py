"""Build and run Linux-host C component tests using the existing Ubuntu GCC."""
from __future__ import annotations
import argparse
import base64
import json
from pathlib import Path
import sys
from firmware_support import connection_arguments,remote_python
ROOT=Path(__file__).resolve().parents[1]
REMOTE=r"""
import base64,json,pathlib,subprocess,tempfile
with tempfile.TemporaryDirectory(prefix='smartbox-components-') as td:
    root=pathlib.Path(td)
    for name,data in payload['files'].items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(base64.b64decode(data))
    version=subprocess.check_output(['gcc','--version']).decode().splitlines()[0]
    results=[]
    for role in ['ENV','POSE']:
        binary=root/role
        cmd=['gcc','-std=c99','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-g',
             '-DSB_ROLE_'+role+'=1','-I'+str(root/'firmware/include'),'-I'+str(root/'tests/firmware'),
             *[str(root/p) for p in payload['sources']],'-o',str(binary)]
        subprocess.run(cmd,check=True)
        for mode in [-1,*range(1,9)]:
            p=subprocess.run([str(binary),str(mode)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=10)
            print(p.stdout.decode(),end='',flush=True)
            if p.returncode:raise RuntimeError('Failed '+role+' mode '+str(mode))
            results.append(role+':'+str(mode))
    for definitions in [[],['-DSB_ROLE_ENV=1','-DSB_ROLE_POSE=1'],['-DSB_ROLE_ENV=0'],['-DSB_ROLE_POSE=2']]:
        p=subprocess.run(['gcc','-std=c99','-fsyntax-only','-I'+str(root/'firmware/include'),*definitions,
                          str(root/'firmware/services/sb_config.c')],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if not p.returncode:raise RuntimeError('Invalid role accepted: '+str(definitions))
        results.append('invalid-role:'+str(definitions))
    print(json.dumps({'compiler':version,'passed':len(results),'cases':results}))
"""
def main():
    p=argparse.ArgumentParser(description=__doc__);connection_arguments(p);p.add_argument('--report',type=Path)
    args=p.parse_args()
    files={f.relative_to(ROOT).as_posix():base64.b64encode(f.read_bytes()).decode() for parent in ['firmware','tests/firmware'] for f in (ROOT/parent).rglob('*') if f.is_file()}
    sources=['firmware/'+name for name in ['app/smartbox_tasks.c','domain/sb_pipeline.c','drivers/sb_sensor_mock.c','drivers/sb_output_mock.c','drivers/sb_platform_cmsis.c','services/sb_config.c','services/sb_clock.c','services/sb_event_buffer.c','services/sb_network_stub.c']]+['tests/firmware/test_components.c']
    code,output=remote_python(args,REMOTE,{'files':files,'sources':sources},check=False)
    print(output,end='')
    if args.report:args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(output,encoding='utf-8')
    return code
if __name__=='__main__':sys.exit(main())
