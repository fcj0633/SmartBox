"""Python 3.8 worker: sanitized SDK snapshot and strict verified installation.
Executed by sdk_bundle.py through authenticated SSH, never as a public service.
"""
import hashlib,json,os,re,shutil,stat,tarfile,tempfile
from pathlib import Path,PurePosixPath

def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()

def canonical(name):
 p=Path(name)
 if not p.is_absolute() or p!=p.resolve() or p in (Path('/'),Path.home()):raise ValueError('Canonical dedicated path required')
 return p

def safe_name(name):
 p=PurePosixPath(name)
 if p.is_absolute() or '..' in p.parts or '\\' in name or ':' in name:raise ValueError('Archive path escape')
 return p

def inventory(root):
 rows={}
 for parent,dirs,files in os.walk(str(root),followlinks=False):
  for name in sorted(dirs+files):
   p=Path(parent)/name;r=p.relative_to(root).as_posix();m=p.lstat()
   if p.is_symlink():
    target=os.readlink(str(p))
    if root not in p.resolve().parents:raise ValueError('External link: '+r)
    rows[r]={'type':'link','target':target,'mode':stat.S_IMODE(m.st_mode)}
   elif p.is_dir():rows[r]={'type':'dir','mode':stat.S_IMODE(m.st_mode)}
   elif p.is_file():rows[r]={'type':'file','mode':stat.S_IMODE(m.st_mode),'size':m.st_size,'sha256':sha(p)}
   else:raise ValueError('Special file rejected')
 return rows

def export():
 sdk=canonical(payload['sdk']);dest=canonical(payload['destination'])
 if dest.exists() or sdk==dest or sdk in dest.parents:raise ValueError('New destination outside SDK required')
 if not (sdk/'build.py').is_file():raise ValueError('SDK missing')
 if (sdk/'.smartbox-build.lock').exists():raise ValueError('SDK is locked by a build; export later')
 if shutil.disk_usage(str(dest.parent)).free<4*1024**3:raise ValueError('Need 4GB free staging space')
 dest.mkdir(mode=0o700);stage=dest/'stage';stage.mkdir();patches=[]
 excluded={'.git','.repo','out','__pycache__','logistics_box'}
 def ignore(directory,names):
  return [n for n in names if n in excluded or n.startswith(('study_','.smartbox')) or n.endswith(('.pyc','.log','.local.json')) or n=='sb_local_config.h']
 shutil.copytree(str(sdk),str(stage/'sdk'),symlinks=True,ignore=ignore)
 # Keep bundled vendor baseline libraries, but remove generated application outputs.
 vendor=stage/'sdk/vendor/hisi/hi3861/hi3861'
 for rel in ('output','build/build_tmp','build/scripts/__pycache__'):
  p=vendor/rel
  if p.is_dir() and not p.is_symlink():shutil.rmtree(str(p));patches.append({'path':'vendor/hisi/hi3861/hi3861/'+rel,'action':'exclude-generated'})
 sample=stage/'sdk/applications/BearPi/BearPi-HM_Nano/sample/BUILD.gn'
 old=sample.read_bytes()
 pattern=rb'(?m)^[ \t]*(?!#)("[^"\r\n]+:[^"\r\n]+"),?[ \t]*\r?$'
 matches=list(re.finditer(pattern,old))
 if len(matches)!=1:raise ValueError('Sample entry not understood')
 m=matches[0];new=old[:m.start(1)]+b'"A1_kernal_thread:thread_example"'+old[m.end(1):]
 # The distributed entry must refer to an actual official example target.
 gn=stage/'sdk/applications/BearPi/BearPi-HM_Nano/sample/A1_kernal_thread/BUILD.gn'
 if not gn.is_file():raise ValueError('Official default sample missing')
 sample.write_bytes(new);patches.append({'path':str(sample.relative_to(stage/'sdk')),'before_sha256':hashlib.sha256(old).hexdigest(),'after_sha256':sha(sample),'action':'select-official-example'})
 tools=stage/'toolchains';tools.mkdir()
 for name,path in payload['toolchains'].items():
  if name not in ('gn','ninja','gcc_riscv32'):raise ValueError('Unsupported toolchain')
  shutil.copytree(str(canonical(path)),str(tools/name),symlinks=True)
 # Redact literal example credentials in application demos only. Never modify the source SDK.
 app=stage/'sdk/applications'
 secret_re=re.compile(r'(?im)(#define\s+\w*(?:PASSWORD|PASSWD|SSID|DEVICE_SECRET)\w*\s+)"[^"\n]*"')
 for p in app.rglob('*'):
  if not p.is_file() or p.suffix not in ('.c','.h') or p.is_symlink():continue
  raw=p.read_bytes()
  try:text=raw.decode('utf-8')
  except UnicodeDecodeError:continue
  updated=secret_re.sub(r'\1"CONFIGURE_LOCALLY"',text)
  if text!=updated:p.write_text(updated);patches.append({'path':str(p.relative_to(stage/'sdk')),'action':'redact-example-credential','before_sha256':hashlib.sha256(raw).hexdigest(),'after_sha256':sha(p)})
 manifests={};archives={};notices=[]
 for name in ('sdk','toolchains'):
  root=stage/name;rows=inventory(root);manifests[name]=rows
  for rel,row in rows.items():
   if row['type']=='file' and any(t in Path(rel).name.lower() for t in ('license','copying','notice','copyright')):
    notices.append({'component':name,'path':rel,'size':row['size'],'sha256':row['sha256']})
  archive=dest/(name+'.tar.gz')
  with tarfile.open(str(archive),'w:gz',compresslevel=5) as tf:tf.add(str(root),arcname=name)
  archives[archive.name]={'sha256':sha(archive),'bytes':archive.stat().st_size}
 result={'format':1,'provenance':'local-derived-snapshot; no verified upstream commit metadata',
  'upstream':'https://gitee.com/bearpi/bearpi-hm_nano','patches':patches,'archives':archives,'manifests':manifests,
  'notices':notices,'redistribution':'local developer handoff only; upstream vendor and toolchain terms require review before public redistribution'}
 (dest/'bundle.json').write_text(json.dumps(result,indent=2));shutil.rmtree(str(stage))
 return {'directory':str(dest),'archives':archives,'manifest_sha256':sha(dest/'bundle.json'),'notice_files':len(notices)}

def install():
 source=canonical(payload['source']);dest=canonical(payload['destination'])
 if dest.exists():raise ValueError('Destination already exists; no merge or overwrite')
 meta=json.loads((source/'bundle.json').read_text(encoding='utf-8'));expected=meta['manifests']
 needed=sum(r.get('size',0) for rows in expected.values() for r in rows.values())
 if shutil.disk_usage(str(dest.parent)).free<needed*2+100*1024**2:raise ValueError('Insufficient extraction space')
 # Validate every archive/member against the trusted manifest before any write.
 # Extract links last, never let tarfile follow a link while writing a file.
 for name,item in meta['archives'].items():
  safe_name(name)
  if sha(source/name)!=item['sha256']:raise ValueError('Archive hash mismatch')
  group=name.removesuffix('.tar.gz') if hasattr(str,'removesuffix') else name[:-7]
  seen=set();actual={}
  with tarfile.open(str(source/name)) as tf:
   for m in tf:
    p=safe_name(m.name)
    if not p.parts or p.parts[0]!=group or not (m.isdir() or m.isfile() or m.issym()):raise ValueError('Invalid archive member')
    normalized=str(p)
    if normalized in seen:raise ValueError('Duplicate archive member')
    seen.add(normalized)
    rel=str(p.relative_to(group))
    if rel=='.':
     if not m.isdir():raise ValueError('Archive root must be a directory')
     continue
    row={'mode':m.mode & 0o7777}
    if m.mode & 0o7000:raise ValueError('Privileged archive mode rejected')
    if m.isdir():row['type']='dir'
    elif m.issym():row.update(type='link',target=m.linkname)
    else:
     h=hashlib.sha256()
     with tf.extractfile(m) as f:
      for b in iter(lambda:f.read(1048576),b''):h.update(b)
     row.update(type='file',size=m.size,sha256=h.hexdigest())
    actual[rel]=row
    if m.issym():
     target=PurePosixPath(m.linkname)
     if target.is_absolute():raise ValueError('Absolute archive link')
     resolved=(dest/str(p)).parent.joinpath(m.linkname).resolve()
     if dest/group not in resolved.parents:raise ValueError('Escaping archive link')
   if actual!=expected[group]:raise ValueError('Archive member manifest mismatch')
   for rel in actual:
    for ancestor in PurePosixPath(rel).parents:
     if actual.get(str(ancestor),{}).get('type')=='link':raise ValueError('Archive writes through link')
 dest.mkdir(mode=0o700)
 try:
  for name in meta['archives']:
   with tarfile.open(str(source/name)) as tf:
    members=tf.getmembers()
    for m in members:
     if m.issym():continue
     path=dest/str(safe_name(m.name))
     if m.isdir():path.mkdir(parents=True,exist_ok=True)
     else:
      path.parent.mkdir(parents=True,exist_ok=True)
      with tf.extractfile(m) as src,path.open('xb') as dst:shutil.copyfileobj(src,dst)
      path.chmod(m.mode & 0o777)
    for m in members:
     if m.issym():os.symlink(m.linkname,str(dest/str(safe_name(m.name))))
    for m in reversed(members):
     if m.isdir():(dest/str(safe_name(m.name))).chmod(m.mode & 0o777)
  for group,rows in expected.items():
   if inventory(dest/group)!=rows:raise ValueError('Extracted file/mode manifest mismatch')
  (dest/'installed-bundle.json').write_text(json.dumps({'manifest_sha256':sha(source/'bundle.json')}))
 except BaseException:
  # Preserve failed staging for diagnosis; never delete a preexisting SDK.
  raise
 return {'installed':str(dest),'verified':True}

if payload['action']=='inspect':
 root=canonical(payload['sdk']);files=0;size=0;notices=0
 for parent,dirs,names in os.walk(str(root),followlinks=False):
  dirs[:]=[n for n in dirs if n not in ('.git','.repo','out')]
  for name in names:
   p=Path(parent)/name
   if p.is_symlink() or not p.is_file():continue
   files+=1;size+=p.stat().st_size
   if any(t in name.lower() for t in ('license','copying','notice','copyright')):notices+=1
 print(json.dumps({'sdk_markers':all((root/n).is_file() for n in ('build.py','bundle.json')),'upstream_git_metadata':(root/'.git').exists(),'files':files,'bytes':size,'notice_files':notices,'free_bytes':shutil.disk_usage(str(root)).free,'active_build_lock':(root/'.smartbox-build.lock').exists()}))
elif payload['action']=='export':print(json.dumps(export()))
elif payload['action']=='install':print(json.dumps(install()))
else:raise ValueError('Unknown bundle action')
