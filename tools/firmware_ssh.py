"""SSH-only controlled synchronization; reuses the exact local three-way algorithm."""
import base64
import json
from pathlib import Path
from firmware_support import remote_python

def synchronize_ssh(args,source,role='ENV',apply=False,restore=False):
    from sync_firmware import safe
    source=safe(source)
    module=(Path(__file__).parent/'sync_firmware.py').read_text(encoding='utf-8')
    # Supply an ordinary __file__ and a private temporary source directory. The SDK
    # state remains at its canonical target, independent of the transport scratch.
    files={p.relative_to(source).as_posix():base64.b64encode(p.read_bytes()).decode()
           for p in source.rglob('*') if p.is_file() and p.suffix in ('.c','.h','.gn','.gni')}
    script='''import tempfile,shutil,base64,json
from pathlib import Path
scratch=Path(tempfile.mkdtemp(prefix='smartbox-sync-source-'))
try:
 for name,data in payload['files'].items():
  p=scratch/'firmware'/name
  if '..' in Path(name).parts or Path(name).is_absolute():raise ValueError('Unsafe source')
  p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(base64.b64decode(data))
 scope={'__name__':'smartbox_sync_module','__file__':str(scratch/'tools/sync_firmware.py')}
 exec(compile(payload['module'],'sync_firmware.py','exec'),scope)
 result=scope['synchronize'](scratch/'firmware',Path(payload['sdk']),payload['role'],payload['apply'],payload['restore'])
 print(json.dumps(result))
finally:shutil.rmtree(str(scratch))
'''
    _,out=remote_python(args,script,dict(files=files,module=module,sdk=args.remote_sdk,role=role,apply=apply,restore=restore))
    return json.loads(out.strip().splitlines()[-1])
