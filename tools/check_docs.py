"""Validate SmartBox's module requirements and documentation without network access."""
from __future__ import annotations
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "docs/requirements/baseline/项目原始需求-v1.0.md"
CR = "docs/management/changes/CR-002-需求体系与编号重构.md"
INDEX = "docs/requirements/README.md"
MATRIX = "docs/management/需求追踪矩阵.md"
SRS = "docs/requirements/00-系统需求规格说明书.md"
HISTORY_HASHES = {
BASELINE:"cc897f8e8b7ad8c101a62a6fffae29345c8179dec39c965f7dd238b57f886694",
"docs/management/changes/CR-001-原始需求规范化.md":"59fd0fcb8264dd538b6a1bef2c1210682c20c0753a1f97b20a674865025c3712",
"docs/management/decisions/ADR-001-系统架构与技术选型.md":"f3c1df8d681b9d071494bf9673d1df82fa36499975b6eace487dacf2643cbdac",
"docs/development/tasks/TASK-000-工程与文档基线.md":"8d65dcd5f9dde07859b9522570af8e789179acc8b896c8d79e4931f216fb94de",
"docs/delivery/releases/REL-000-P0基线.md":"bbb5680233f30f987c177508c42ad2304aa34aea9a46e2ce1d090562a33d283f",
"docs/testing/reports/P0-文档基线验收.md":"27af0db55673ac8314ee2f8174dfc517110223fd7566a8035198f6a0f03113a1"}
MODULE_NAMES = ["物流箱运行监测","安全防护与哨兵","设备接入与控制","事件管理与可靠性","物流箱管理与状态聚合","用户与权限管理"]
MODULE_FILES = {f"MOD-{i:02}": f"docs/requirements/modules/MOD-{i:02}-{name}.md" for i,name in enumerate(MODULE_NAMES,1)}
INITIAL_IDS = {f"REQ-M{m:02}-{n:03}" for m,count in enumerate((14,6,8,8,5,6),1) for n in range(1,count+1)}
REQ_RE = r"REQ-M\d{2}-\d{3}"
NFR_RE = r"NFR-\d{3}"
LINK_RE = re.compile(r"\[([^\]\n]+)\]\(([^)\n]+)\)")
REQUIRED_DIRS = """firmware/app firmware/domain firmware/drivers firmware/services firmware/include firmware/build backend frontend contracts/mqtt contracts/http deploy tools tests artifacts docs/management/decisions docs/management/changes docs/requirements/baseline docs/requirements/product docs/requirements/modules docs/requirements/quality docs/design/architecture docs/design/hardware docs/design/firmware docs/design/backend docs/design/frontend docs/development/environment docs/development/guides docs/development/tasks docs/development/iterations docs/development/troubleshooting docs/testing/cases docs/testing/integration docs/testing/reports docs/testing/evidence docs/delivery/releases docs/delivery/deployment docs/delivery/manuals docs/delivery/demonstration docs/references docs/templates""".split()

@dataclass
class Result:
    errors: list[str]
    stats: dict[str, int]

def prose(text: str) -> str:
    return re.sub(r"```[\s\S]*?```", "", text)

def field(text: str, name: str) -> str:
    match = re.search(r"^\| "+re.escape(name)+r" \| (.*?) \|$",text,re.M)
    return match[1] if match else ""

def ids(text: str) -> set[str]:
    return set(re.findall(REQ_RE+"|"+NFR_RE,text))

def anchors(text: str) -> set[str]:
    result = set(re.findall(r'<a\s+id=["\x27]([^"\x27]+)',text))
    counts: dict[str,int] = {}
    for heading in re.findall(r"^#{1,6}\s+(.+)$",prose(text),re.M):
        heading = re.sub(r"\[([^]]+)\]\([^)]+\)",r"\1",heading)
        slug = "".join(c for c in heading.lower() if c in "-_ " or not unicodedata.category(c).startswith(("P","S")))
        slug = re.sub(r"\s","-",slug.strip())
        n=counts.get(slug,0); counts[slug]=n+1
        result.add(slug+(f"-{n}" if n else ""))
    return result

def validate(root: Path) -> Result:
    root=root.resolve()
    errors: list[str]=[]
    def check(ok: bool, message: str) -> None:
        if not ok: errors.append(message)
    docs: dict[str,str]={}
    for p in root.rglob("*.md"):
        parts=p.relative_to(root).parts
        if any(x in parts for x in (".git","artifacts","node_modules","target",".venv")):continue
        try: docs[p.relative_to(root).as_posix()]=p.read_text(encoding="utf-8-sig")
        except (OSError,UnicodeError) as exc: errors.append(f"Read error: {p}: {exc}")
    for p,digest in HISTORY_HASHES.items():
        target=root/p
        check(target.is_file(),f"History missing: {p}")
        if target.is_file():check(hashlib.sha256(target.read_bytes()).hexdigest()==digest,f"History bytes changed: {p}")
    check(not (root/"需求文档.md").exists(),"Original root file not moved")
    for directory in REQUIRED_DIRS:check((root/directory).is_dir(),f"Directory missing: {directory}")
    check(not list((root/"docs/requirements/features").glob("*.md")),"Old feature documents still exist")
    definitions: dict[str,tuple[str,str]]={}
    blocks: dict[str,str]={}
    for name,text in docs.items():
        if name==BASELINE:continue
        top=re.match(r"# ((?:MOD-\d{2}|(?:NFR|TASK|TC|ADR|CR|ITER|REL)-\d{3}))\b",text)
        if top:
            key=top[1];check(key not in definitions,f"Duplicate definition: {key}")
            definitions[key]=(name,"")
        if name.startswith("docs/requirements/modules/"):
            matches=list(re.finditer(r"^### ("+REQ_RE+r")\b[^\n]*",text,re.M))
            for i,match in enumerate(matches):
                rid=match[1]
                check(rid not in definitions,f"Duplicate definition: {rid}")
                definitions[rid]=(name,rid.lower())
                blocks[rid]=text[match.start():matches[i+1].start() if i+1<len(matches) else len(text)]
                module="MOD-"+rid[5:7]
                check(MODULE_FILES.get(module)==name,f"Wrong module: {rid}")
                check(field(blocks[rid],"所属模块")==module,f"Module metadata mismatch: {rid}")
                check(rid.lower() in anchors(text),f"Requirement anchor missing: {rid}")
    for module,name in MODULE_FILES.items():
        check(definitions.get(module)==(name,""),f"Module missing or wrong file: {module}")
    check({x for x in definitions if x.startswith("MOD-")}==set(MODULE_FILES),"Module inventory mismatch")
    req_ids={x for x in definitions if x.startswith("REQ-M")}
    check(INITIAL_IDS<=req_ids,f"Initial requirements missing: {sorted(INITIAL_IDS-req_ids)}")
    for prefix,start,stop in (("NFR",1,3),("TASK",0,10),("TC",1,12)):
        for number in range(start,stop+1):
            key=f"{prefix}-{number:03}";check(key in definitions,f"Document missing: {key}")
    for key in ("ADR-001","CR-001","CR-002","ITER-001","REL-000"):
        check(key in definitions,f"Document missing: {key}")
    for p in (INDEX,MATRIX,SRS,CR):check(p in docs,f"Document missing: {p}")
    link_count=0
    for name,text in docs.items():
        if name==BASELINE:continue
        current=prose(text)
        if name not in HISTORY_HASHES:
            scanned=current
            if name==CR:
                check(scanned.count("<!-- migration-audit:start -->")==1 and scanned.count("<!-- migration-audit:end -->")==1,"Migration audit markers invalid")
                scanned=re.sub(r"<!-- migration-audit:start -->[\s\S]*?<!-- migration-audit:end -->","",scanned)
            check(not re.search(r"\bREQ-\d{3}\b|requirements/features|features/REQ",scanned),f"Legacy reference in current document: {name}")
            for candidate in re.findall(r"\bREQ-M[0-9][0-9A-Za-z-]*",scanned):
                check(bool(re.fullmatch(REQ_RE,candidate)),f"Malformed requirement identifier: {name} -> {candidate}")
            for rid in re.findall(REQ_RE+"|"+NFR_RE+r"|MOD-\d{2}|(?:TASK|TC|ADR|CR|ITER|REL)-\d{3}",scanned):
                # REL-001 is an explicit planned release, not an implemented document.
                if rid=="REL-001":continue
                check(rid in definitions,f"Undefined reference: {name} -> {rid}")
        for _,target in LINK_RE.findall(current):
            target=target.strip().strip("<>")
            if re.match(r"[A-Za-z][A-Za-z0-9+.-]*:",target):continue
            target,_,anchor=target.partition("#")
            dest=(root/name).parent/unquote(target) if target else root/name
            dest=dest.resolve()
            link_count+=1
            check(dest.exists(),f"Broken link: {name} -> {target}")
            if anchor and dest.is_file() and dest.suffix==".md":
                key=dest.relative_to(root).as_posix() if dest.is_relative_to(root) else ""
                check(unquote(anchor) in anchors(docs.get(key,"")),f"Broken anchor: {name} -> {target}#{anchor}")
    index_rows=[]
    for line in docs.get(INDEX,"").splitlines():
        if re.match(r"\| MOD-\d{2} \|",line):
            index_rows.extend(re.findall(REQ_RE,line))
    check(set(index_rows)==req_ids,"Index inventory mismatch")
    check(all(n==1 for n in Counter(index_rows).values()),"Duplicate requirement in index")
    rows: dict[str,list[str]]={}
    for line in docs.get(MATRIX,"").splitlines():
        if not re.match(r"\| (?:MOD-\d{2}|全局) \|",line):continue
        cells=[x.strip() for x in line.strip("|").split("|")]
        if len(cells)!=7: errors.append("Malformed trace row: "+line);continue
        module,rid,link,design,tasks,tcs,phase=cells
        check(rid not in rows,f"Duplicate trace row: {rid}");rows[rid]=cells
        check(rid in definitions,f"Undefined trace requirement: {rid}")
        check(module==("MOD-"+rid[5:7] if rid.startswith("REQ-M") else "全局"),f"Trace module mismatch: {rid}")
        check(bool(phase),f"Trace phase missing: {rid}")
        dlinks=LINK_RE.findall(design)
        check(bool(dlinks),f"Design link missing: {rid}")
        for _,dest in dlinks:
            check((root/MATRIX).parent.joinpath(dest).resolve().is_relative_to(root/"docs/design"),f"Not a design document: {rid}")
        task_ids=set(re.findall(r"TASK-\d{3}",tasks));tc_ids=set(re.findall(r"TC-\d{3}",tcs))
        check(bool(task_ids) and bool(tc_ids),f"Orphan trace: {rid}")
        for key in task_ids|tc_ids:check(key in definitions,f"Undefined trace document: {key}")
        block=blocks.get(rid,docs.get(definitions.get(rid,("",""))[0],""))
        check(set(re.findall(r"TASK-\d{3}",field(block,"TASK")))==task_ids,f"TASK metadata mismatch: {rid}")
        check(set(re.findall(r"TC-\d{3}",field(block,"TC")))==tc_ids,f"TC metadata mismatch: {rid}")
        meta_design=field(block,"Design")
        def targets(value,from_path):
            return {((root/from_path).parent/d.split("#")[0]).resolve() for _,d in LINK_RE.findall(value)}
        check(targets(meta_design,definitions.get(rid,("",""))[0])==targets(design,MATRIX),f"Design metadata mismatch: {rid}")
        if rid in definitions:
            expected_file,expected_anchor=definitions[rid]
            check(any(((root/MATRIX).parent/d.partition("#")[0]).resolve()==root/expected_file and d.partition("#")[2]==expected_anchor for _,d in LINK_RE.findall(link)),f"Trace requirement link mismatch: {rid}")
    check(set(rows)==req_ids|{f"NFR-{i:03}" for i in range(1,4)},"Trace inventory mismatch")
    for kind,limit in (("TASK",10),("TC",12)):
        for i in range(1,limit+1):
            key=f"{kind}-{i:03}"
            if key not in definitions:continue
            text=docs[definitions[key][0]]
            expected={rid for rid,c in rows.items() if key in re.findall(kind+r"-\d{3}",c[4 if kind=="TASK" else 5])}
            check(ids(field(text,"需求"))==expected,f"Reverse {kind} mismatch: {key}")
    for name,text in docs.items():
        if not name.startswith("docs/design/"):continue
        section=text.partition("## 业务模块与需求映射")[2]
        expected={rid for rid,c in rows.items() if any(((root/MATRIX).parent/d.split("#")[0]).resolve()==root/name for _,d in LINK_RE.findall(c[3]))}
        check(ids(section)==expected,f"Reverse design mismatch: {name}")
    for i in range(1,15):
        check(f"Q-{i:02}" in docs.get(SRS,""),f"Pending issue missing: Q-{i:02}")
    audit=re.findall(r"^\| REQ-(\d{3}) / 规则(\d+) \| ([a-f0-9]{64}) \| \[("+REQ_RE+r")\]\([^)]+\) \|$",docs.get(CR,""),re.M)
    expected_audit={(f"{i:03}",str(j)) for i,n in enumerate((7,6,8,7,6,8,8,7,7),1) for j in range(1,n+1)}
    check({(a,b) for a,b,_,_ in audit}==expected_audit and len(audit)==64,"Migration coverage mismatch")
    for old,num,digest,rid in audit:
        raw_bullets=re.findall(r"^- (.+)$",blocks.get(rid,""),re.M)
        check(any(hashlib.sha256(b.encode()).hexdigest()==digest for b in raw_bullets),f"Migrated rule missing or changed: {old}/{num} -> {rid}")
    try:
        examples=json.loads((root/"contracts/mqtt/examples.json").read_text(encoding="utf-8"))
        for name,payload in examples.items():
            check(len(json.dumps(payload,ensure_ascii=False,separators=(",",":")).encode())<=1536,f"Example exceeds payload limit: {name}")
            check(payload.get("v")==1 and payload.get("box_id")=="BOX-0001",f"Example identity/version invalid: {name}")
        check(examples["property"]["caps"]==71,"ENV capability missing")
        check(examples["cmd"]["target_caps"]&71==examples["cmd"]["target_caps"],"Unsupported example command")
        check(examples["event_ack"]["event_seqs"]==[x["event_seq"] for x in examples["event"]["events"]],"Event example ack mismatch")
        check(examples["command_ack"]["request_id"]==examples["cmd"]["request_id"],"Command example ack mismatch")
        check(examples["unsynced_property"]["ts"] is None and examples["unsynced_property"]["ts_src"]=="UNSYNCED","Unsynced example invalid")
    except (OSError,ValueError,KeyError,TypeError) as exc:errors.append(f"MQTT example invalid: {exc}")
    return Result(errors,dict(modules=len([x for x in definitions if x.startswith("MOD-")]),requirements=len(req_ids),trace_rows=len(rows),migration_rules=len(audit),links=link_count,markdown=len(docs)))

def main() -> int:
    result=validate(ROOT)
    if result.errors:
        for error in result.errors: print("FAIL:",error)
        return 1
    print("PASS:",json.dumps(result.stats,ensure_ascii=False))
    print("Scope: documentation, migration and examples only; no application or hardware validation.")
    return 0

if __name__=="__main__":raise SystemExit(main())
