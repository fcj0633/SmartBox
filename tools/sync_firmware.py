"""Controlled one-way firmware copy. Preview is strictly read-only (Python stdlib)."""
from __future__ import annotations
import argparse
import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

ROOT=Path(__file__).resolve().parents[1]
SAMPLE=Path("applications/BearPi/BearPi-HM_Nano/sample")
TARGET=SAMPLE/"logistics_box"
STATE=SAMPLE/".smartbox-sync.json"
JOURNAL=SAMPLE/".smartbox-sync-pending.json"
LOCK=SAMPLE/".smartbox-sync.lock"

def digest(data): return hashlib.sha256(data).hexdigest()
def b64(data): return base64.b64encode(data).decode("ascii")
def unb64(data): return base64.b64decode(data)
def safe(path, recursive=True):
    path=Path(os.path.abspath(path))
    for item in [path,*path.parents]:
        if item.is_symlink() or (item.exists() and getattr(item.lstat(),"st_file_attributes",0)&0x400):
            raise ValueError("Link/reparse point rejected: "+str(item))
    if recursive and path.is_dir():
        for parent,dirs,files in os.walk(path,followlinks=False):
            for name in dirs+files:
                child=Path(parent)/name
                if child.is_symlink() or getattr(child.lstat(),"st_file_attributes",0)&0x400:
                    raise ValueError("Link/reparse point rejected: "+str(child))
    return path

def validate_roots(source,sdk):
    source=safe(source);sdk=safe(sdk,False)
    safe(sdk/SAMPLE,False);safe(sdk/TARGET)
    for item in (STATE,JOURNAL,LOCK,SAMPLE/"BUILD.gn"):safe(sdk/item,False)
    if source==sdk or source in sdk.parents or sdk in source.parents: raise ValueError("Overlapping source/SDK")
    for item in ["bundle.json","build.py",str(SAMPLE/"BUILD.gn")]:
        if not (sdk/item).is_file(): raise ValueError("Not a supported SDK: "+item)
    if not (source/"include/sb_config.h").is_file(): raise ValueError("Not SmartBox firmware source")
    return source,sdk

def atomic(path,data):
    temp=path.with_name(path.name+".tmp")
    if temp.exists() or temp.is_symlink(): raise ValueError("Unexpected temporary file: "+str(temp))
    temp.write_bytes(data);os.replace(temp,path)

def encoded(data): return (json.dumps(data,ensure_ascii=False,indent=2)+"\n").encode()
def read_state(sdk):
    state=sdk/STATE
    if not state.exists(): return None
    value=json.loads(state.read_text(encoding="utf-8"))
    if value.get("format")!=1 or value.get("sdk")!=str(sdk): raise ValueError("State identity mismatch")
    for name in value["files"]:
        if Path(name).is_absolute() or Path(name).drive or ".." in Path(name).parts or "\\" in name: raise ValueError("Invalid managed path")
    return value

def desired_files(source,role):
    files={p.relative_to(source).as_posix():p.read_bytes() for p in sorted(source.rglob("*")) if p.is_file() and p.suffix in (".c",".h",".gn",".gni") and p.parts[-2:]!=("build","selected_role.gni")}
    files["build/selected_role.gni"]=(source/"build/roles"/(role+".gni")).read_bytes()
    fingerprint=digest(encoded({name:digest(data) for name,data in files.items()}))
    files["build/source-fingerprint.txt"]=(fingerprint+"\n").encode()
    return files,fingerprint

def select_entry(original):
    # Preserve comments, line endings and bytes (the SDK file may contain legacy encodings).
    matches=list(re.finditer(rb"(?m)^[ \t]*(?!#)(\"[^\"\r\n]+:[^\"\r\n]+\"),?[ \t]*\r?$",original))
    if len(matches)!=1: raise ValueError("Expected exactly one active sample feature")
    m=matches[0];return original[:m.start(1)]+b'"logistics_box/build:smartbox"'+original[m.end(1):]

def plan(source,sdk,role,restore=False):
    source,sdk=validate_roots(source,sdk)
    if (sdk/JOURNAL).exists(): raise ValueError("Interrupted sync: use --recover-sync --apply")
    state=read_state(sdk);entry=(sdk/SAMPLE/"BUILD.gn").read_bytes()
    if not state and (sdk/TARGET).exists(): raise ValueError("Unknown existing logistics_box directory")
    original=unb64(state["original_entry"]) if state else entry
    last=unb64(state["last_entry"]) if state else entry
    if entry not in (original,last): raise ValueError("Build entry conflict")
    if restore:
        if not state: raise ValueError("No saved entry")
        desired={};fingerprint=state["fingerprint"]
    else: desired,fingerprint=desired_files(source,role)
    old=state["files"] if state else {}
    changes=[]
    if not restore:
        for name in sorted(set(old)|set(desired)):
            dest=sdk/TARGET/name;current=dest.read_bytes() if dest.is_file() else None
            if name in old and (current is None or digest(current)!=old[name]): raise ValueError("SDK copy conflict: "+name)
            if name not in old and current is not None: raise ValueError("Unknown target file conflict: "+name)
            new=desired.get(name)
            if current!=new: changes.append((dest,current,new))
    selected=original if restore else select_entry(original)
    if entry!=selected: changes.append((sdk/SAMPLE/"BUILD.gn",entry,selected))
    new_state={"format":1,"sdk":str(sdk),"original_entry":b64(original),"last_entry":b64(selected),
               "role":state["role"] if restore else role,"fingerprint":fingerprint,
               "files":old if restore else {n:digest(d) for n,d in desired.items()}}
    return sdk,changes,new_state

@contextmanager
def locked(sdk):
    path=sdk/LOCK
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    os.write(fd,str(os.getpid()).encode());os.close(fd)
    try: yield
    finally: path.unlink()

def recover(sdk):
    safe(sdk,False);safe(sdk/TARGET);journal=sdk/JOURNAL
    if not journal.exists(): raise ValueError("No recovery journal")
    value=json.loads(journal.read_text())
    for row in value:
        rel=Path(row["path"])
        if rel.is_absolute() or rel.drive or ".." in rel.parts: raise ValueError("Recovery path escape")
        p=sdk/rel
        if p!=sdk/STATE and p!=sdk/SAMPLE/"BUILD.gn" and sdk/TARGET not in p.parents: raise ValueError("Unmanaged recovery target")
        cur=p.read_bytes() if p.is_file() else None
        if cur not in (unb64(row["before"]) if row["before"] is not None else None,
                       unb64(row["after"]) if row["after"] is not None else None): raise ValueError("Recovery conflict: "+str(p))
    for row in reversed(value):
        p=sdk/row["path"]
        if row["before"] is None:
            if p.exists():p.unlink()
        else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(unb64(row["before"]))
    journal.unlink()

def synchronize(source,sdk,role="ENV",apply=False,restore=False):
    sdk,changes,state=plan(source,sdk,role,restore)
    summary={"sdk":str(sdk),"role":state["role"],"fingerprint":state["fingerprint"],"apply":apply,
             "changes":[{"path":str(p.relative_to(sdk)),"action":"delete" if after is None else "add" if before is None else "modify"} for p,before,after in changes]}
    if not apply:return summary
    with locked(sdk):
        # Recompute under lock so source/target changes invalidate the preview.
        _,again,new_state=plan(source,sdk,role,restore)
        if again!=changes or new_state!=state:raise ValueError("Preview changed; retry")
        state_path=sdk/STATE
        changes.append((state_path,state_path.read_bytes() if state_path.exists() else None,encoded(state)))
        rows=[{"path":p.relative_to(sdk).as_posix(),"before":b64(before) if before is not None else None,
               "after":b64(after) if after is not None else None} for p,before,after in changes]
        atomic(sdk/JOURNAL,encoded(rows))
        try:
            for p,before,after in changes:
                current=p.read_bytes() if p.is_file() else None
                if current!=before:raise ValueError("Concurrent target change: "+str(p))
                if after is None:p.unlink()
                else:p.parent.mkdir(parents=True,exist_ok=True);atomic(p,after)
            (sdk/JOURNAL).unlink()
        except Exception:
            recover(sdk);raise
    return summary

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source",type=Path,default=ROOT/"firmware")
    p.add_argument("--sdk-root",type=Path,required=True)
    p.add_argument("--role",choices=["ENV","POSE"],default="ENV")
    p.add_argument("--apply",action="store_true");p.add_argument("--restore-entry",action="store_true")
    p.add_argument("--recover-sync",action="store_true")
    args=p.parse_args()
    try:
        if args.recover_sync:
            if not args.apply:raise ValueError("Recovery requires --apply; inspect journal first")
            # A stale lock is never silently removed; inspect owner before manual removal.
            with locked(args.sdk_root):recover(args.sdk_root)
            result={"recovered":True}
        else:result=synchronize(args.source,args.sdk_root,args.role,args.apply,args.restore_entry)
        print(json.dumps(result,ensure_ascii=False,indent=2));return 0
    except (ValueError,OSError,KeyError) as error:print("Sync rejected: "+str(error),file=sys.stderr);return 1
if __name__=="__main__":sys.exit(main())
