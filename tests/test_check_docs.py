"""Regression tests for documentation validation; all mutations use temporary copies."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("smartbox_check_docs",ROOT/"tools/check_docs.py")
checker=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=checker
spec.loader.exec_module(checker)

class DocumentationChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.seed_tmp=tempfile.TemporaryDirectory(prefix="smartbox-doc-seed-")
        cls.seed=Path(cls.seed_tmp.name)/"repo"
        # Copy only the documentation fixture and its required directories, never Git or secrets.
        cls.seed.mkdir()
        for directory in checker.REQUIRED_DIRS:(cls.seed/directory).mkdir(parents=True,exist_ok=True)
        for p in ROOT.rglob("*.md"):
            if any(x in p.relative_to(ROOT).parts for x in (".git","artifacts","node_modules","target",".venv")):continue
            q=cls.seed/p.relative_to(ROOT);q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
        shutil.copytree(ROOT/"backend/src",cls.seed/"backend/src",dirs_exist_ok=True)
        shutil.copy2(ROOT/"backend/pom.xml",cls.seed/"backend/pom.xml")
        shutil.copytree(ROOT/"tools/simulator",cls.seed/"tools/simulator",dirs_exist_ok=True,ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copytree(ROOT/"tests/simulator",cls.seed/"tests/simulator",dirs_exist_ok=True,ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copytree(ROOT/"deploy/mosquitto",cls.seed/"deploy/mosquitto",dirs_exist_ok=True)
        shutil.copytree(ROOT/"contracts",cls.seed/"contracts",dirs_exist_ok=True)
        shutil.copytree(ROOT/"firmware",cls.seed/"firmware",dirs_exist_ok=True)
        shutil.copytree(ROOT/"tests/firmware",cls.seed/"tests/firmware",dirs_exist_ok=True)
        for p in (ROOT/"tools").iterdir():
            if p.is_file():shutil.copy2(p,cls.seed/"tools"/p.name)
        for p in (ROOT/"tests").glob("*.py"):
            shutil.copy2(p,cls.seed/"tests"/p.name)
        for relative in ("tools/check_docs.py","tests/test_check_docs.py"):
            shutil.copy2(ROOT/relative,cls.seed/relative)

    @classmethod
    def tearDownClass(cls):
        cls.seed_tmp.cleanup()

    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix="smartbox-doc-case-")
        self.root=Path(self.tmp.name)/"repo"
        shutil.copytree(self.seed,self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def text(self,path):return (self.root/path).read_text(encoding="utf-8")
    def write(self,path,text):(self.root/path).write_text(text,encoding="utf-8",newline="\n")
    def fail_with(self,fragment):
        errors=checker.validate(self.root).errors
        self.assertTrue(any(fragment in error for error in errors),(fragment,errors))

    def module(self,n=1):return checker.MODULE_FILES[f"MOD-{n:02}"]

    def test_valid_fixture_without_git_or_network(self):
        self.assertFalse((self.root/".git").exists())
        result=checker.validate(self.root)
        self.assertEqual([],result.errors)
        self.assertEqual(47,result.stats["requirements"])
        self.assertEqual(50,result.stats["trace_rows"])
        self.assertEqual(64,result.stats["migration_rules"])

    def test_duplicate_requirement_definition(self):
        p=self.module();self.write(p,self.text(p)+"\n### REQ-M01-001 duplicate\n")
        self.fail_with("Duplicate definition")

    def test_missing_requirement_definition(self):
        p=self.module();self.write(p,self.text(p).replace("### REQ-M01-001 ","### removed ",1))
        self.fail_with("Initial requirements missing")

    def test_requirement_in_wrong_module(self):
        p=self.module();self.write(p,self.text(p).replace("### REQ-M01-001 ","### REQ-M02-007 ",1))
        self.fail_with("Wrong module")

    def test_unknown_reference(self):
        self.write("README.md",self.text("README.md")+"\nREQ-M01-999\n")
        self.fail_with("Undefined reference")

    def test_malformed_requirement_identifier(self):
        self.write("README.md",self.text("README.md")+"\nREQ-M1-003\n")
        self.fail_with("Malformed requirement identifier")

    def test_missing_module(self):
        (self.root/self.module(6)).unlink()
        self.fail_with("Module missing")

    def test_missing_anchor(self):
        p=self.module();self.write(p,self.text(p).replace('<a id="req-m01-001"></a>',"",1))
        self.fail_with("anchor")

    def test_broken_local_link(self):
        self.write("README.md",self.text("README.md")+"\n[missing](docs/no-such-file.md)\n")
        self.fail_with("Broken link")

    def test_orphan_trace_without_task(self):
        p=checker.MATRIX
        lines=self.text(p).splitlines()
        for i,line in enumerate(lines):
            if line.startswith("| MOD-01 | REQ-M01-001 |"):
                cells=line.strip("|").split("|");cells[4]=" ";lines[i]="|"+"|".join(cells)+"|";break
        self.write(p,"\n".join(lines)+"\n")
        self.fail_with("Orphan trace")

    def test_reverse_task_association_missing(self):
        p=next((self.root/"docs/development/tasks").glob("TASK-007-*.md")).relative_to(self.root).as_posix()
        self.write(p,re.sub(r"^\| 需求 \|.*$","| 需求 | |",self.text(p),flags=re.M))
        self.fail_with("Reverse TASK")

    def test_reverse_test_association_missing(self):
        p=next((self.root/"docs/testing/cases").glob("TC-001-*.md")).relative_to(self.root).as_posix()
        self.write(p,re.sub(r"^\| 需求 \|.*$","| 需求 | |",self.text(p),flags=re.M))
        self.fail_with("Reverse TC")

    def test_duplicate_index_entry(self):
        p=checker.INDEX;line=next(x for x in self.text(p).splitlines() if x.startswith("| MOD-01 |"))
        self.write(p,self.text(p)+"\n"+line+"\n")
        self.fail_with("Duplicate requirement in index")

    def test_legacy_identifier_in_current_document(self):
        legacy="REQ-"+"001"
        self.write("README.md",self.text("README.md")+"\n"+legacy+"\n")
        self.fail_with("Legacy reference")

    def test_cr_audit_exemption_does_not_cover_current_text(self):
        self.write(checker.CR,self.text(checker.CR)+"\nREQ-"+"001\n")
        self.fail_with("Legacy reference")

    def test_current_file_cannot_hide_in_history_directory(self):
        p="docs/testing/reports/new-report.md"
        self.write(p,"# Current report\n\nREQ-"+"001\n")
        self.fail_with("Legacy reference")

    def test_immutable_history_bytes(self):
        p=next(p for p in checker.HISTORY_HASHES if p!=checker.BASELINE)
        self.write(p,self.text(p)+"\nchanged\n")
        self.fail_with("History bytes changed")

    def test_original_requirement_hash(self):
        p=self.root/checker.BASELINE;p.write_bytes(p.read_bytes()+b"changed")
        self.fail_with("History bytes changed")

    def test_migration_rule_cannot_disappear(self):
        p=self.module();old="ENV 每 1 秒采集温度、湿度、光照，记录有效标记；读取失败不填零伪装正常。"
        self.assertIn(old,self.text(p));self.write(p,self.text(p).replace(old,"changed",1))
        self.fail_with("Migrated rule missing")

    def test_migration_manifest_must_cover_all_rules(self):
        p=checker.CR;s=self.text(p)
        s=re.sub(r"^\| REQ-001 / 规则1 \|.*\n","",s,flags=re.M)
        self.write(p,s);self.fail_with("Migration coverage mismatch")

    def test_pending_issue_cannot_disappear(self):
        p=checker.SRS;self.write(p,self.text(p).replace("Q-14","fact-note"))
        self.fail_with("Pending issue missing")

    def test_mqtt_ack_checks_retained(self):
        p="contracts/mqtt/examples.json";j=json.loads(self.text(p));j["event_ack"]["event_seqs"]=[]
        self.write(p,json.dumps(j));self.fail_with("Event example ack mismatch")

    def test_mqtt_size_checks_retained(self):
        p="contracts/mqtt/examples.json";j=json.loads(self.text(p));j["property"]["extra"]="x"*1600
        self.write(p,json.dumps(j));self.fail_with("Example exceeds payload limit")

if __name__=="__main__":unittest.main()
