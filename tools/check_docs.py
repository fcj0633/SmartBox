"""Read-only checks for the P0 documentation baseline (Python 3.10+)."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "docs/requirements/baseline/项目原始需求-v1.0.md"
EXPECTED_HASH = "cc897f8e8b7ad8c101a62a6fffae29345c8179dec39c965f7dd238b57f886694"
ERRORS: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        ERRORS.append(message)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def main() -> int:
    check(BASELINE.is_file(), "Original requirements missing")
    if BASELINE.is_file():
        check(hashlib.sha256(BASELINE.read_bytes()).hexdigest() == EXPECTED_HASH,
              "Original requirement bytes changed")
    check(not (ROOT / "需求文档.md").exists(), "Root requirements not moved")
    required_dirs = (
        "firmware/app firmware/domain firmware/drivers firmware/services firmware/include "
        "firmware/build backend frontend contracts/mqtt contracts/http deploy tools tests artifacts "
        "docs/management/decisions docs/management/changes docs/requirements/baseline "
        "docs/requirements/product docs/requirements/features docs/requirements/quality "
        "docs/design/architecture docs/design/hardware docs/design/firmware docs/design/backend "
        "docs/design/frontend docs/development/environment docs/development/guides "
        "docs/development/tasks docs/development/iterations docs/development/troubleshooting "
        "docs/testing/cases docs/testing/integration docs/testing/reports docs/testing/evidence "
        "docs/delivery/releases docs/delivery/deployment docs/delivery/manuals "
        "docs/delivery/demonstration docs/references docs/templates"
    ).split()
    for directory in required_dirs:
        check((ROOT / directory).is_dir(), f"Directory missing: {directory}")

    markdown = sorted(p for p in ROOT.rglob("*.md")
                      if ".git" not in p.parts and "artifacts" not in p.relative_to(ROOT).parts)
    links = 0
    definitions: dict[str, Path] = {}
    for path in markdown:
        if path == BASELINE:
            continue  # Original input is immutable, not the maintained specification.
        content = read(path)
        match = re.match(r"# ((?:REQ|NFR|TASK|TC|ADR|CR|ITER|REL)-\d{3})\b", content)
        if match:
            key = match.group(1)
            check(key not in definitions, f"Duplicate document id: {key}")
            definitions[key] = path
        # Code samples and inline protocol notation are not Markdown links.
        prose = re.sub(r"```[\s\S]*?```", "", content)
        for target in re.findall(r"\[[^\]\n]+\]\(([^)\n]+)\)", prose):
            target = target.strip().strip("<>")
            if re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("#"):
                continue
            local = unquote(target.split("#", 1)[0])
            check((path.parent / local).resolve().exists(),
                  f"Broken link: {path.relative_to(ROOT)} -> {target}")
            links += 1

    for prefix, start, stop in (("REQ", 1, 9), ("NFR", 1, 3),
                                ("TASK", 0, 10), ("TC", 1, 12)):
        for number in range(start, stop + 1):
            key = f"{prefix}-{number:03}"
            check(key in definitions, f"Missing document: {key}")
    for key in ("ADR-001", "CR-001", "ITER-001", "REL-000"):
        check(key in definitions, f"Missing document: {key}")

    matrix_path = ROOT / "docs/management/需求追踪矩阵.md"
    matrix = read(matrix_path)
    rows = {}
    for line in matrix.splitlines():
        if not re.match(r"\| (REQ|NFR)-\d{3} \|", line):
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        key = cells[0]
        check(key not in rows, f"Duplicate matrix row: {key}")
        rows[key] = cells
        if len(cells) != 6:
            ERRORS.append(f"Malformed matrix row: {key}")
            continue
        check(bool(cells[2]), f"No design mapping: {key}")
        task_ids = set(re.findall(r"TASK-\d{3}", cells[3]))
        tc_ids = set(re.findall(r"TC-\d{3}", cells[4]))
        check(bool(task_ids) and bool(tc_ids), f"Incomplete trace: {key}")
        for reference in task_ids | tc_ids:
            check(reference in definitions, f"Undefined trace id: {key} -> {reference}")
        for tc in tc_ids:
            if tc in definitions:
                check(key in read(definitions[tc]), f"TC missing reverse requirement: {tc} -> {key}")
        if key.startswith("REQ") and key in definitions:
            for label, expected in (("关联任务", task_ids), ("验收用例", tc_ids)):
                meta = next((line for line in read(definitions[key]).splitlines()
                             if line.startswith(f"| {label} |")), "")
                actual = set(re.findall(r"(?:TASK|TC)-\d{3}", meta))
                check(actual == expected, f"Requirement metadata/matrix mismatch: {key} {label}")
    for key in definitions:
        if key.startswith(("REQ-", "NFR-")):
            check(key in rows, f"No trace row: {key}")

    example_path = ROOT / "contracts/mqtt/examples.json"
    try:
        examples = json.loads(read(example_path))
        for name, payload in examples.items():
            check(len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()) <= 1536,
                  f"Example exceeds payload limit: {name}")
            check(payload.get("v") == 1, f"Example wrong version: {name}")
            check(payload.get("box_id") == "BOX-0001", f"Example wrong box: {name}")
        check(examples["property"]["caps"] == 71, "ENV sentry capability missing")
        check(examples["cmd"]["target_caps"] & 71 == examples["cmd"]["target_caps"],
              "Example command unsupported by ENV")
        check(examples["event_ack"]["event_seqs"] ==
              [item["event_seq"] for item in examples["event"]["events"]],
              "Example event confirmation mismatch")
        check(examples["command_ack"]["request_id"] == examples["cmd"]["request_id"],
              "Example command confirmation mismatch")
        check(examples["unsynced_property"]["ts"] is None and
              examples["unsynced_property"]["ts_src"] == "UNSYNCED", "Invalid unsynced example")
    except (OSError, ValueError, KeyError, TypeError) as error:
        ERRORS.append(f"Invalid MQTT examples: {error}")

    if ERRORS:
        for error in ERRORS:
            print(f"FAIL: {error}")
        return 1
    print(f"PASS: original SHA256 preserved; {len(required_dirs)} directories; "
          f"{len(markdown)} Markdown files inspected; {links} local links; "
          f"{len(definitions)} document IDs; {len(rows)} trace rows; MQTT examples consistent.")
    print("Scope: documentation structure and examples only; no application or hardware validation.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
