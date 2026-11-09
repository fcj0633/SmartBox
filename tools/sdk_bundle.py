"""Create/check/install a private SDK handoff without committing SDK binaries."""
from __future__ import annotations
import argparse,hashlib,json,subprocess,sys,zipfile
from pathlib import Path
from firmware_support import remote_python,remote_command
from project import read_workspace,connection,ROOT

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1048576),b''):h.update(block)
    return h.hexdigest()

def verify(directory):
    value=json.loads((directory/'bundle.json').read_text(encoding='utf-8'))
    if value.get('format')!=1 or set(value['archives'])!={'sdk.tar.gz','toolchains.tar.gz'}:raise ValueError('Unknown bundle')
    for name,row in value['archives'].items():
        if digest(directory/name)!=row['sha256'] or (directory/name).stat().st_size!=row['bytes']:raise ValueError('Bundle checksum mismatch')
    return value

def export(args,c):
    if args.bundle.exists():raise ValueError('New local output directory required')
    w=read_workspace(args.workspace)['ubuntu'];paths=w.get('tool_paths',[])
    if len(paths)!=3:raise ValueError('Three explicit GN/Ninja/GCC paths required')
    destination=args.destination
    if not destination:raise ValueError('New remote staging --destination required')
    script=(ROOT/'tools/sdk_bundle_remote.py').read_text(encoding='utf-8')
    _,out=remote_python(c,script,{'action':'export','sdk':c.remote_sdk,'destination':destination,
        'toolchains':{'gn':paths[0],'ninja':paths[1],'gcc_riscv32':str(Path(paths[2]).as_posix()).removesuffix('/bin')}})
    result=json.loads(out.strip().splitlines()[-1]);args.bundle.mkdir(parents=True)
    for name in ('bundle.json','sdk.tar.gz','toolchains.tar.gz'):
        with (args.bundle/name).open('wb') as f:
            r=remote_command(c,['cat',destination+'/'+name],stdout=f,stderr=subprocess.PIPE)
        if r.returncode:raise ValueError('Bundle download failed; partial output retained')
    verify(args.bundle)
    (args.bundle/'START-HERE.md').write_text('# SmartBox 开发交付包\n\n先核对 bundle.json 的哈希，再阅读源码中的 docs/delivery/deployment/开发者完整部署指南.md。\n\nSDK 与工具链来自已验证的本地快照，不宣称对应上游某个 Git 提交。保留全部组件许可证。当前交付供本地开发交接；公开再分发前须核对厂商二进制、工具链对应源码及各组件条款。不得认为 SDK 根目录 Apache 许可证覆盖所有组件。\n\nWindows Java、MySQL、Mosquitto、Python、Node 需单独安装，Maven/npm/pip 依赖首次联网解析；本包不是整机离线镜像。\n',encoding='utf-8')
    print(json.dumps(result,indent=2));return 0

def install(args,c):
    verify(args.bundle)
    if not args.destination:raise ValueError('New remote destination required')
    # Upload into a freshly created remote directory. No remote shell interpolation.
    import base64
    _,out=remote_python(c,"import tempfile,json\nprint(json.dumps({'path':tempfile.mkdtemp(prefix='smartbox-sdk-upload-')}))")
    upload=json.loads(out.strip())['path']
    import shlex
    for name in ('bundle.json','sdk.tar.gz','toolchains.tar.gz'):
        remote_path=upload+'/'+name
        script="import sys;from pathlib import Path;p=Path("+repr(remote_path)+");p.write_bytes(sys.stdin.buffer.read())"
        with (args.bundle/name).open('rb') as stream:
            r=remote_command(c,['python3','-c',script],stdin=stream,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if r.returncode:raise ValueError('SDK upload failed')
    script=(ROOT/'tools/sdk_bundle_remote.py').read_text(encoding='utf-8')
    _,out=remote_python(c,script,{'action':'install','source':upload,'destination':args.destination})
    print(out);return 0

def source(args):
    verify(args.bundle)
    # Exact tracked committed tree, never a recursive filesystem copy.
    if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT):raise ValueError('Commit reviewed changes before source delivery')
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    archive=args.bundle/'SmartBox-source.zip'
    if archive.exists():raise ValueError('Source archive already exists; do not overwrite')
    subprocess.run(['git','archive','--format=zip','--prefix=SmartBox/','--output',str(archive),head],cwd=ROOT,check=True)
    with zipfile.ZipFile(archive) as z:
        if any('/secrets/' in n or n.endswith(('sb_local_config.h','AGENTS.override.md')) for n in z.namelist()):raise ValueError('Private source entry detected')
        files={n.removeprefix('SmartBox/'):hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
    with zipfile.ZipFile(archive,'a',compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('SmartBox/SOURCE.json',json.dumps({'format':1,'commit':head,'files':files},indent=2)+'\n')
    meta={'commit':head,'source_sha256':digest(archive),'source_bytes':archive.stat().st_size,'sdk_manifest_sha256':digest(args.bundle/'bundle.json')}
    (args.bundle/'source.json').write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps(meta));return 0

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['inspect','export','verify','install','source'])
    p.add_argument('--workspace',type=Path,default=ROOT/'secrets/workspace.local.json');p.add_argument('--bundle',type=Path);p.add_argument('--destination')
    a=p.parse_args()
    try:
        if a.action=='inspect':
            c=connection(read_workspace(a.workspace))
            _,out=remote_python(c,(ROOT/'tools/sdk_bundle_remote.py').read_text(encoding='utf-8'),{'action':'inspect','sdk':c.remote_sdk})
            print(out);return 0
        if not a.bundle:raise ValueError('--bundle required')
        a.bundle=a.bundle.resolve()
        if a.action=='verify':verify(a.bundle);print('PASS: SDK archive checksums');return 0
        if a.action=='source':return source(a)
        c=connection(read_workspace(a.workspace))
        return export(a,c) if a.action=='export' else install(a,c)
    except (ValueError,OSError,KeyError,RuntimeError,subprocess.SubprocessError) as e:
        print('SDK delivery failed: '+type(e).__name__+'; inspect private output; no credentials printed.',file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
