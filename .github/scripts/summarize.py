"""Always leave a compact report index, including incomplete/failed scans."""
import json
import os
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

destination = os.environ.get("GITHUB_STEP_SUMMARY")
if destination:
    lines = ["\n### Available analysis reports", "", "| Report | Findings / status |", "|---|---|"]
    for path in sorted(Path("reports").glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8-sig"))
            if path.name.startswith("trivy-"):
                counts = Counter(v.get("Severity", "UNKNOWN") for item in data.get("Results", []) for v in item.get("Vulnerabilities", []))
                detail = ", ".join(f"{severity}: {counts[severity]}" for severity in ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"))
            elif path.name == "semgrep.json":
                detail = f"{len(data['results'])} findings, {len(data.get('errors', []))} analysis errors"
            elif isinstance(data, list):
                count = sum(len(item.get("messages", [])) for item in data) if path.name == "eslint.json" else len(data)
                detail = f"{count} findings"
            else:
                detail = "available"
        except (ValueError, KeyError):
            detail = "INVALID / incomplete"
        lines.append(f"| `{path.name}` | {detail} |")
    if Path("backend/target/spotbugsXml.xml").exists():
        count = len(ET.parse("backend/target/spotbugsXml.xml").getroot().findall("BugInstance"))
        lines.append(f"| SpotBugs (backend) | {count} high-priority findings |")
    lines.extend(["", "Workflow/Dockerfile/ShellCheck diagnostics are in the named step logs. A failed or skipped step is not a clean scan.", ""])
    with open(destination, "a", encoding="utf-8") as stream:
        stream.write("\n".join(lines))
