"""Fast live probes available in the repository job (no npm install required)."""
import json
from pathlib import Path
import tempfile

from probe_scanners import invoke

with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    secret = "gh" + "p_" + "q7Nw9Jx2Rt6Vk4Yz8Bm3Ha5Sd1Lc0Pe9Xu7A"
    (root / "credentials.txt").write_text("token = " + secret, encoding="utf-8")
    report = root / "report.json"
    invoke(["gitleaks", "dir", "--redact", "--report-format", "json", "--report-path", str(report), str(root)], (1,))
    assert json.loads(report.read_text(encoding="utf-8"))
    assert secret not in report.read_text(encoding="utf-8")
    workflow = root / "unsafe.yml"
    workflow.write_text('name: probe\non: issues\njobs:\n  probe:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo "${{ github.event.issue.title }}"\n', encoding="utf-8")
    invoke(["zizmor", "--offline", "--min-severity", "high", str(workflow)], (10, 11, 12, 13, 14))
print("PASS: real secret and unsafe workflow detectors")
