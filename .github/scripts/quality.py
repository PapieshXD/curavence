"""Normalize findings and gate new issues. Scanner errors are never baselineable."""
import argparse
from collections import Counter
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / ".github/quality/baseline.json"
EXCEPTIONS = ROOT / ".github/quality/exceptions.json"


def exceptions(path=EXCEPTIONS, today=None):
    today = today or date.today()
    entries = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(entries, list):
        raise ValueError("Exceptions must be an array")
    for entry in entries:
        for field in ("id", "tool", "reason", "owner", "created", "expires"):
            if not entry.get(field):
                raise ValueError(f"Exception missing {field}")
        created, expires = date.fromisoformat(entry["created"]), date.fromisoformat(entry["expires"])
        if not created <= today <= expires or not 0 < (expires - created).days <= 30:
            raise ValueError(f"Expired or invalid exception: {entry['id']}")
        if entry["tool"] == "gitleaks":
            raise ValueError("Secrets cannot be excepted")
    return entries


def relative(path):
    path = str(path).replace("\\", "/")
    root = str(ROOT).replace("\\", "/") + "/"
    if path.startswith(root):
        path = path[len(root):]
    if path.startswith("src/"):
        path = "frontend/" + path
    return path


def fingerprint(rule, path, context):
    # Whitespace and line shifts do not reset old findings; repeated findings are counted.
    context = " ".join(context.split())
    return f"{rule}|{relative(path)}|{hashlib.sha256(context.encode()).hexdigest()[:20]}"


def source_line(path, number):
    resolved = Path(path)
    if not resolved.is_absolute():
        resolved = ROOT / path
    return resolved.read_text(encoding="utf-8").splitlines()[number - 1]


def findings(tool, report):
    if tool == "spotbugs":
        root = ET.parse(report).getroot()
        if root.tag != "BugCollection" or root.find("Errors") is not None and int(root.find("Errors").get("errors", "0")):
            raise ValueError("SpotBugs analysis errors")
        return [f"{bug.get('type')}|{bug.get('instanceHash')}" for bug in root.findall("BugInstance")]
    data = json.loads(report.read_text(encoding="utf-8-sig"))
    if tool == "eslint":
        found = []
        for file in data:
            for item in file["messages"]:
                if item.get("fatal") or item.get("ruleId") is None:
                    raise ValueError("ESLint parser/configuration error")
                if item["severity"] == 2:
                    found.append(fingerprint(item["ruleId"], file["filePath"], source_line(file["filePath"], item["line"])))
        return found
    if tool == "ruff":
        return [fingerprint(item["code"], item["filename"], source_line(item["filename"], item["location"]["row"])) for item in data]
    if tool == "semgrep":
        if data.get("errors"):
            raise ValueError("Semgrep did not analyze all requested files")
        return [fingerprint(item["check_id"], item["path"], source_line(item["path"], item["start"]["line"]))
                for item in data["results"] if item["extra"]["severity"] == "ERROR"]
    if tool == "trivy":
        if data.get("SchemaVersion") != 2 or "Results" not in data:
            raise ValueError("Invalid Trivy report")
        if not any(result.get("Packages") for result in data["Results"]):
            raise ValueError("Trivy did not inventory any packages")
        return sorted({f"{item['VulnerabilityID']}|{item['PkgName']}|{item['InstalledVersion']}"
                       for result in data["Results"] for item in result.get("Vulnerabilities", [])
                       if item.get("Severity") in ("HIGH", "CRITICAL") and item.get("FixedVersion")})
    raise ValueError(f"Unsupported tool: {tool}")


def gate(tool, report, baseline_path=BASELINE, exception_path=EXCEPTIONS, record=False, strict=False, comparison=None):
    entries = exceptions(exception_path)
    found = Counter(findings(tool, report))
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    if record:
        if tool == "trivy":
            raise ValueError("Vulnerabilities require expiring exceptions, never a baseline")
        baseline[tool] = dict(sorted(found.items()))
        baseline_path.write_text(json.dumps(baseline, indent=2) + "\n", encoding="utf-8")
        return 0
    known = Counter() if strict or tool == "trivy" else Counter(baseline.get(tool, {}))
    if tool == "trivy" and comparison is not None and not strict:
        known = Counter(findings(tool, comparison))
    remaining = found - known
    allowed = {entry["id"] for entry in entries if entry["tool"] == tool}
    remaining = Counter({key: count for key, count in remaining.items()
                         if key not in allowed and key.split("|")[0] not in allowed})
    print(f"{tool}: {sum(found.values())} findings, {sum(remaining.values())} blocking")
    for key, count in remaining.items():
        print(f"  {count} x {key}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"\n### {tool}\n\n| Findings | Blocking | Report |\n|---:|---:|---|\n| {sum(found.values())} | {sum(remaining.values())} | `{report.as_posix()}` |\n")
    return int(bool(remaining))


def scan(args):
    report = ROOT / "reports" / f"{args.tool}.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    if args.tool == "ruff":
        command = [sys.executable, "-m", "ruff", "check", "imaging-ai-service", "--output-format=json"]
        cwd = ROOT
    elif args.tool == "eslint":
        command = ["node", "node_modules/eslint/bin/eslint.js", "src", "--format=json"]
        cwd = ROOT / "frontend"
    elif args.tool == "semgrep":
        executable = Path(sys.executable).parent / ("semgrep.exe" if os.name == "nt" else "semgrep")
        command = [str(executable), "scan", "--config", ".github/quality/semgrep.yml",
                   "--metrics=off", "--disable-version-check", "--no-git-ignore", "--json", "backend/src", "frontend/src", "imaging-ai-service/app"]
        cwd = ROOT
    else:
        raise ValueError("Use gate for an existing report")
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, check=False)
    # ESLint/Ruff use 1 for findings and 2 for errors. Semgrep uses 0 without --error.
    accepted = (0,) if args.tool == "semgrep" else (0, 1)
    report.write_bytes(result.stdout)
    if result.returncode not in accepted:
        raise RuntimeError(f"{args.tool} failed with exit {result.returncode}")
    return gate(args.tool, report, record=args.record)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["scan", "gate", "exceptions"])
    parser.add_argument("tool", nargs="?", choices=["ruff", "eslint", "semgrep", "spotbugs", "trivy"])
    parser.add_argument("report", nargs="?", type=Path)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--comparison", type=Path)
    args = parser.parse_args()
    if args.command == "exceptions":
        exceptions()
        return 0
    if args.command == "scan":
        return scan(args)
    return gate(args.tool, args.report, record=args.record, strict=args.strict, comparison=args.comparison)


if __name__ == "__main__":
    sys.exit(main())
