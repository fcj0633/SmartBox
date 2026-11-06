"""Ubuntu worker sent through stdin by build_firmware; Python 3.8 compatible.
Never run as a service. SDK mutations are restricted to recorded build outputs.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile
import tempfile
import time

SAMPLE="applications/BearPi/BearPi-HM_Nano/sample/BUILD.gn"
OUT="out/BearPi-HM_Nano"
VENDOR="vendor/hisi/hi3861/hi3861"
PROTECTED=[SAMPLE,OUT,VENDOR]

def sha_file(p):
    h=hashlib.sha256()
    with open(str(p),'rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def manifest(root):
    result={}
    def visit(p):
        info=p.lstat();name=p.relative_to(root).as_posix()
        row={'mode':stat.S_IMODE(info.st_mode)}
        if p.is_symlink():row.update(type='link',target=os.readlink(str(p)))
        elif p.is_file():row.update(type='file',size=info.st_size,sha256=sha_file(p))
        elif p.is_dir():row.update(type='dir')
        else:raise RuntimeError('Unsupported backup object '+name)
        result[name]=row
        if row['type']=='dir':
            for child in sorted(p.iterdir()):visit(child)
    for name in PROTECTED:
        p=root/name
        if p.exists():visit(p)
    return result

def validated(root):
    root=Path(root)
    if not root.is_absolute() or root!=root.resolve():raise RuntimeError('SDK root must be canonical, no links')
    if root==Path('/') or root==Path.home():raise RuntimeError('Unsafe SDK root')
    for name in ['build.py','bundle.json',SAMPLE]:
        if not (root/name).is_file():raise RuntimeError('SDK marker missing: '+name)
    for name in PROTECTED:
        p=root/name
        if not str(p.resolve()).startswith(str(root)+'/') or p.is_symlink():raise RuntimeError('Unsafe protected target')
    return root

def competing_build(root):
    for proc in (Path('/proc').iterdir() if Path('/proc').exists() else []):
        if not proc.name.isdigit() or int(proc.name)==os.getpid():continue
        try:
            cmd=(proc/'cmdline').read_bytes().replace(b'\x00',b' ').decode(errors='replace')
            cwd=(proc/'cwd').resolve()
            if (cwd==root or root in cwd.parents) and any(x in cmd for x in ['build.py','ninja','scons','riscv32-unknown-elf-gcc']):
                raise RuntimeError('Another build is active: pid '+proc.name)
        except (PermissionError,FileNotFoundError,ProcessLookupError):pass

def save(work,state):
    p=work/'state.json';temp=work/'state.tmp';temp.write_text(json.dumps(state,indent=2));os.replace(str(temp),str(p))

def backup(root):
    competing_build(root)
    lock=root/'.smartbox-build.lock'
    fd=os.open(str(lock),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    work=Path(tempfile.mkdtemp(prefix='smartbox-task002-'))
    os.write(fd,str(work).encode());os.close(fd)
    try:
        before=manifest(root)
        size=sum(x.get('size',0) for x in before.values())
        if shutil.disk_usage(str(root)).free<size*4+100*1024*1024:raise RuntimeError('Insufficient backup/build disk space')
        archive=work/'original-sdk.tar'
        with tarfile.open(str(archive),'w') as tf:
            for name in PROTECTED:
                if (root/name).exists():tf.add(str(root/name),arcname=name,recursive=True)
        # Read every archived file and compare permissions and bytes, not just the tar header.
        verified={}
        with tarfile.open(str(archive),'r') as tf:
            for member in tf:
                name=member.name.rstrip('/');row={'mode':member.mode}
                if member.isdir():row.update(type='dir')
                elif member.issym():row.update(type='link',target=member.linkname)
                elif member.isfile():
                    row.update(type='file',size=member.size,sha256=hashlib.sha256(tf.extractfile(member).read()).hexdigest())
                elif member.islnk():
                    # SDK is expected not to use hard links; don't silently downgrade preservation.
                    raise RuntimeError('Hardlink requires explicit backup support: '+name)
                else:raise RuntimeError('Unsafe tar member '+name)
                verified[name]=row
        if verified!=before or manifest(root)!=before:raise RuntimeError('Backup manifest mismatch / concurrent write')
        state={'format':1,'root':str(root),'work':str(work),'before':before,'backup_sha256':sha_file(archive),
               'phase':'backed-up','created':time.time(),'roles':[],'expected':before}
        save(work,state)
        return {'work':str(work),'archive':str(archive),'sha256':state['backup_sha256'],
                'file_count':sum(x['type']=='file' for x in before.values()),'bytes':size}
    except Exception:
        # No official build has run: preserve backup for inspection, release our lock.
        lock.unlink();raise

def load(root,work):
    work=Path(work)
    if work!=work.resolve() or not work.name.startswith('smartbox-task002-'):raise RuntimeError('Unsafe work directory')
    state=json.loads((work/'state.json').read_text())
    if state['root']!=str(root) or state['work']!=str(work):raise RuntimeError('Recovery identity mismatch')
    if (root/'.smartbox-build.lock').read_text()!=str(work):raise RuntimeError('Build lock ownership mismatch')
    return work,state

def capture(root,work,state):
    state['expected']=manifest(root);save(work,state)

def build(root,work,state,role):
    competing_build(root)
    if role not in ['ENV','POSE']:raise RuntimeError('Invalid role')
    entry=(root/SAMPLE).read_bytes()
    if b'"logistics_box/build:smartbox"' not in entry:raise RuntimeError('SmartBox entry not selected')
    selected=root/'applications/BearPi/BearPi-HM_Nano/sample/logistics_box/build/selected_role.gni'
    if selected.read_text().strip()!='sb_role = "'+role+'"':raise RuntimeError('Role mismatch')
    state['phase']='building-'+role;state['expected']=None;save(work,state)
    env=os.environ.copy()
    env['PATH']=str(Path.home()/'gn')+':'+str(Path.home()/'ninja')+':'+str(Path.home()/'gcc_riscv32/bin')+':'+env['PATH']
    # Resolve and execute all tools BEFORE the official script that removes product out.
    versions={}
    tools=[['python','--version'],['gn','--version'],['ninja','--version'],['scons','--version'],
           ['riscv32-unknown-elf-gcc','--version'],['riscv32-unknown-elf-ld','--version']]
    try:
        for tool in tools:
            result=subprocess.run(tool,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True)
            lines=result.stdout.decode(errors='replace').splitlines()
            versions[tool[0]]=next((line for line in lines if line.startswith('SCons by Steven Knight')),lines[0])
        log=work/(role+'.log');start=time.time()
        with log.open('wb') as stream:
            process=subprocess.run(['python','build.py','BearPi-HM_Nano'],cwd=str(root),env=env,stdout=stream,stderr=subprocess.STDOUT)
        result={'role':role,'exit_code':process.returncode,'start':start,'end':time.time(),'tools':versions,'log':str(log),
                'command':'python build.py BearPi-HM_Nano'}
        state['roles'].append(result);capture(root,work,state)
        if process.returncode:raise RuntimeError('SDK build failed: '+role+'; log '+str(log))
        product=root/OUT
        objects=list(product.rglob('libsmartbox.a'))
        if len(objects)!=1:raise RuntimeError('Expected exactly one new SmartBox archive')
        symbols=subprocess.check_output(['riscv32-unknown-elf-nm',str(objects[0])],env=env).decode()
        required=['SmartBoxMain','sb_config_get','sb_app_start','sb_sensor_read','sb_clock_get','sb_network_poll']
        if any((' T '+name+'\n') not in symbols for name in required):raise RuntimeError('Missing application object symbols')
        maps=list(product.glob('*.map'))
        if len(maps)!=1:raise RuntimeError('Missing final link map')
        map_text=maps[0].read_text(errors='replace')
        if 'libsmartbox.a' not in map_text or 'SmartBoxMain' not in map_text:raise RuntimeError('Application missing from final link')
        bins=list(product.glob('*allinone.bin'))
        if len(bins)!=1 or bins[0].stat().st_mtime<start:raise RuntimeError('No fresh final image')
        # Confirm the linked, uncompressed application contains the selected node identity.
        elfs=list((root/VENDOR/'output/bin').glob('*.out'))+list((root/VENDOR/'output/bin').glob('*.elf'))
        linked=[]
        for elf in elfs:
            data=elf.read_bytes()
            if ('BOX-0001-'+role).encode() in data:
                nm=subprocess.check_output(['riscv32-unknown-elf-nm',str(elf)],env=env).decode(errors='replace')
                if 'SmartBoxMain' in nm and 'sb_app_start' in nm:linked.append(str(elf.relative_to(root)))
        if not linked:raise RuntimeError('No final linked ELF with role identity and application symbols')
        archive=work/(role+'-product.tar')
        with tarfile.open(str(archive),'w') as tf:
            tf.add(str(product),arcname='product')
            for name in linked:tf.add(str(root/name),arcname='linked/'+Path(name).name)
        result.update(product_archive=str(archive),product_sha256=sha_file(archive),
                      bin_sha256=sha_file(bins[0]),map_sha256=sha_file(maps[0]),linked=linked,
                      application_archive=str(objects[0].relative_to(root)),application_symbols=required,
                      source_fingerprint=(selected.parent/'source-fingerprint.txt').read_text().strip())
        state['phase']='built-'+role;capture(root,work,state)
        return result
    except BaseException:
        # Only this still-running worker knows which outputs its child just changed.
        # Abrupt host/process loss leaves expected=None: automatic recovery then refuses.
        if state['expected'] is None:capture(root,work,state)
        state['phase']='failed-'+role;save(work,state);raise

def restore(root,work,state):
    competing_build(root)
    if state.get('expected') is None:raise RuntimeError('Interrupted build: unknown output ownership; inspect before recovery')
    current=manifest(root)
    # The controlled entry may have been restored by sync_firmware immediately before this operation.
    expected=state['expected'];original=state['before']
    entry=current.get(SAMPLE);before_entry=original.get(SAMPLE);last_entry=expected.get(SAMPLE)
    # Samba atomic replacement can preserve the restored bytes but keep the last
    # controlled Unix mode. Accept only these two recorded modes; tar restores it.
    restored_entry=dict(before_entry,mode=entry['mode']) if entry and before_entry else None
    if entry==restored_entry and entry['mode'] in (before_entry['mode'],last_entry['mode']):
        expected=dict(expected);expected[SAMPLE]=entry
    if current!=expected:raise RuntimeError('Concurrent changes detected; backup retained, no restore overwrite')
    archive=work/'original-sdk.tar'
    if sha_file(archive)!=state['backup_sha256']:raise RuntimeError('Backup checksum changed')
    state['phase']='restoring';save(work,state)
    # All destructive targets are explicit, canonical paths inside the validated SDK root.
    for name in [OUT,VENDOR]:
        p=root/name
        if p.is_symlink() or root not in p.resolve().parents:raise RuntimeError('Unsafe restore target')
        if p.exists():shutil.rmtree(str(p))
    with tarfile.open(str(archive),'r') as tf:
        for member in tf:
            p=root/member.name
            if Path(member.name).is_absolute() or '..' in Path(member.name).parts:raise RuntimeError('Tar traversal')
            if not any(member.name==n or member.name.startswith(n+'/') for n in PROTECTED):raise RuntimeError('Unexpected tar path')
        tf.extractall(str(root))
    restored=manifest(root)
    if restored!=original:raise RuntimeError('Restored file hash / permissions mismatch')
    state['phase']='restored';state['expected']=restored;state['restored_at']=time.time();save(work,state)
    (root/'.smartbox-build.lock').unlink()
    return {'restored':True,'manifest_entries':len(restored),'file_count':sum(x['type']=='file' for x in restored.values()),
            'entry_sha256':sha_file(root/SAMPLE),'output_files':sum(k.startswith(OUT+'/') and v['type']=='file' for k,v in restored.items())}

root=validated(payload['sdk'])
action=payload['action']
if action=='probe':
    competing_build(root)
    target=root/'applications/BearPi/BearPi-HM_Nano/sample/logistics_box/build/source-fingerprint.txt'
    print(json.dumps({'entry_sha256':sha_file(root/SAMPLE),'source_fingerprint':target.read_text().strip() if target.exists() else None}))
elif action=='backup':print(json.dumps(backup(root)))
else:
    work,state=load(root,payload['work'])
    if action=='build':result=build(root,work,state,payload['role'])
    elif action=='restore':result=restore(root,work,state)
    elif action=='capture-entry':
        # Only the sample selection is allowed to change outside the build worker.
        cur=manifest(root);expected=dict(state['expected']);expected[SAMPLE]=cur[SAMPLE]
        if cur!=expected:raise RuntimeError('Protected output changed before role switch')
        state['expected']=cur;save(work,state);result={'entry_captured':True}
    else:raise RuntimeError('Unknown action')
    print(json.dumps(result))
