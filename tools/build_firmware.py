"""Backup, build both SDK roles, archive fresh products, restore the learning environment."""
from __future__ import annotations
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
from firmware_support import connection_arguments,remote_python,remote_command,source_identity
from sync_firmware import ROOT,SAMPLE,STATE,TARGET,digest,synchronize,validate_roots
WORKER=(ROOT/'tools/firmware_remote.py').read_text(encoding='utf-8')

def write_json(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def remote(args,action,work=None,**fields):
    code,output=remote_python(args,WORKER,dict(action=action,sdk=args.remote_sdk,work=work,tool_paths=getattr(args,'tool_path',[]),**fields),check=False)
    if code:raise RuntimeError(output)
    return json.loads(output.strip().splitlines()[-1])

def download(args,remote_path,destination,expected=None):
    destination.parent.mkdir(parents=True,exist_ok=True)
    with destination.open('wb') as stream:
        result=remote_command(args,['cat',remote_path],stdout=stream,stderr=subprocess.PIPE)
    if result.returncode:raise RuntimeError('Download failed: '+result.stderr.decode(errors='replace'))
    checksum=hashlib.file_digest(destination.open('rb'),'sha256').hexdigest() if sys.version_info>=(3,11) else file_hash(destination)
    if expected and checksum!=expected:raise RuntimeError('Archive checksum mismatch')
    return checksum

def file_hash(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def snapshot_source(run):
    files={}
    with tarfile.open(run/'source-files.tar','w') as archive:
        for parent in ['firmware','tools','tests']:
            for path in sorted((ROOT/parent).rglob('*')):
                if '__pycache__' in path.parts or not path.is_file() or path.suffix not in ('.c','.h','.gn','.gni','.py','.txt','.md'):
                    continue
                if path.is_symlink():raise ValueError('Source snapshot link rejected')
                relative=path.relative_to(ROOT).as_posix()
                files[relative]=file_hash(path);archive.add(path,arcname=relative,recursive=False)
    write_json(run/'source-files.json',files)
    return file_hash(run/'source-files.tar')

def sync(args,source,role='ENV',apply=False,restore=False):
    if args.transport=='ssh':
        from firmware_ssh import synchronize_ssh
        return synchronize_ssh(args,source,role,apply,restore)
    return synchronize(source,args.sdk_root,role,apply,restore)

def restore(args,run,state):
    print('Restoring original learning entry and SDK build outputs...',flush=True)
    sync(args,ROOT/'firmware',apply=True,restore=True)
    result=remote(args,'restore',state['backup']['work'])
    state['restore']=result;state['phase']='restored';write_json(run/'run.json',state)
    download(args,state['backup']['work']+'/state.json',run/'recovery-state.json')
    return result

def execute(args):
    source=ROOT/'firmware'
    if args.transport=='mapped':
        if not args.sdk_root:raise ValueError('Mapped transport requires --sdk-root')
        source,sdk=validate_roots(source,args.sdk_root);args.sdk_root=sdk
    else:sdk=None
    if args.recover:
        run=args.recover.resolve();state=json.loads((run/'run.json').read_text(encoding='utf-8'))
        if state['sdk_mapping']!=str(sdk) or state['remote_sdk']!=args.remote_sdk:raise ValueError('Recovery SDK mismatch')
        result=restore(args,run,state);print(json.dumps(result,ensure_ascii=False));return result
    run=args.artifacts.resolve()/datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    run.mkdir(parents=True,exist_ok=False)
    identity=source_identity(ROOT)
    state={'phase':'preparing','sdk_mapping':str(sdk),'remote_sdk':args.remote_sdk,'roles':[],
           'source_commit':identity['commit'],'worktree':identity['worktree']}
    state['source_archive_sha256']=snapshot_source(run)
    write_json(run/'run.json',state)
    (run/'source.diff').write_bytes(identity['diff'])
    probe=remote(args,'probe')
    if sdk and probe['entry_sha256']!=digest((sdk/SAMPLE/'BUILD.gn').read_bytes()):raise RuntimeError('Mapping/SSH entry mismatch')
    print('Creating and verifying Unix-permission-preserving SDK backup...',flush=True)
    state['backup']=remote(args,'backup');state['phase']='backed-up';write_json(run/'run.json',state)
    try:
        download(args,state['backup']['archive'],run/'original-sdk.tar',state['backup']['sha256'])
        state['local_backup_verified']=True;write_json(run/'run.json',state)
        print('Backup verified locally. No official build has run yet.',flush=True)
        for role in ['ENV','POSE']:
            preview=sync(args,source,role)
            write_json(run/(role+'-preview.json'),preview)
            print(role+': preview '+str(len(preview['changes']))+' controlled file changes.',flush=True)
            applied=sync(args,source,role,apply=True)
            # Match a generated content fingerprint AND entry bytes through both access paths.
            probe=remote(args,'probe')
            if probe['source_fingerprint']!=applied['fingerprint'] or (sdk and probe['entry_sha256']!=digest((sdk/SAMPLE/'BUILD.gn').read_bytes())):
                raise RuntimeError('Mapping and SSH do not refer to the same copied application')
            remote(args,'capture-entry',state['backup']['work'])
            print(role+': compiling and linking in Ubuntu; full output goes into the role log.',flush=True)
            try:result=remote(args,'build',state['backup']['work'],role=role)
            finally:
                # Save the actual log even when compile/link fails.
                download(args,state['backup']['work']+'/'+role+'.log',run/role/'build.log')
            download(args,result['product_archive'],run/role/'product.tar',result['product_sha256'])
            # Extract only our generated product archive inside the ignored role directory.
            with tarfile.open(run/role/'product.tar') as tf:
                for member in tf:
                    if member.issym() or member.islnk() or Path(member.name).is_absolute() or '..' in Path(member.name).parts:
                        raise ValueError('Unsafe product member')
                tf.extractall(run/role)
            result.update(box_id='BOX-0001',node_id='BOX-0001-'+role,capabilities=71 if role=='ENV' else 120,
                          source_commit=state['source_commit'],source_archive_sha256=state['source_archive_sha256'],worktree_dirty=bool(state['worktree']),local_directory=str(run/role))
            write_json(run/role/'manifest.json',result);state['roles'].append(result);write_json(run/'run.json',state)
            print(role+': compile/link and linked identity verified; image SHA256 '+result['bin_sha256'],flush=True)
        if state['roles'][0]['bin_sha256']==state['roles'][1]['bin_sha256']:raise RuntimeError('Roles produced the same final image')
        state['builds_passed']=True;write_json(run/'run.json',state)
    except BaseException as error:
        state['failure']=str(error);write_json(run/'run.json',state);raise
    finally:
        result=restore(args,run,state)
        print(json.dumps(result,ensure_ascii=False),flush=True)
        print('Evidence: '+str(run),flush=True)
    return state

def main():
    p=argparse.ArgumentParser(description=__doc__);connection_arguments(p)
    p.add_argument('--transport',choices=['mapped','ssh'],default='mapped');p.add_argument('--tool-path',action='append',default=[]);p.add_argument('--sdk-root',type=Path);p.add_argument('--remote-sdk',required=True)
    p.add_argument('--artifacts',type=Path,default=ROOT/'artifacts/firmware/TASK-002')
    p.add_argument('--recover',type=Path,help='Recover a prior run directory; refuses unowned/concurrent changes')
    args=p.parse_args()
    try:execute(args);return 0
    except Exception as error:print('Build stopped; inspect retained run.json / backup: '+str(error),file=sys.stderr);return 1
if __name__=='__main__':sys.exit(main())
