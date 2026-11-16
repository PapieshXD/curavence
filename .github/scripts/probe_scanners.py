"""Smoke-test the actual scanners with temporary synthetic inputs; never commit fixtures."""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def invoke(command, expected, **kwargs):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode not in expected:
        raise RuntimeError(f"Probe failed ({result.returncode}): {command[0]}")
    return result.stdout


def main():
    # A fresh temp directory also proves that scanning does not depend on tracked fixtures.
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        secret = "gh" + "p_" + "q7Nw9Jx2Rt6Vk4Yz8Bm3Ha5Sd1Lc0Pe9Xu7A"
        (root / "credentials.txt").write_text("token = " + secret + "\n", encoding="utf-8")
        report = root / "secrets.json"
        invoke(["gitleaks", "dir", "--no-banner", "--redact", "--report-format", "json",
                "--report-path", str(report), str(root)], (1,))
        findings = json.loads(report.read_text(encoding="utf-8"))
        assert findings and secret not in report.read_text(encoding="utf-8"), "Secret probe must be redacted"
        (root / "credentials.txt").unlink()

        workflow = root / "unsafe.yml"
        workflow.write_text('name: probe\non: issues\njobs:\n  probe:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo "${{ github.event.issue.title }}"\n', encoding="utf-8")
        invoke(["zizmor", "--offline", "--min-severity", "high", str(workflow)], (10, 11, 12, 13, 14))

        output = invoke(["node", "node_modules/eslint/bin/eslint.js", "--stdin", "--stdin-filename", "src/ci-probe.ts", "--format=json"],
                        (1,), input=b"const unusedProbe = 1;\n", cwd=ROOT / "frontend")
        assert any(item["messages"] for item in json.loads(output))

        npm = root / "npm"
        npm.mkdir()
        (npm / "package-lock.json").write_text(json.dumps({
            "name": "scanner-probe", "version": "1.0.0", "lockfileVersion": 3,
            "packages": {"": {"name": "scanner-probe", "version": "1.0.0", "dependencies": {"lodash": "4.17.20"}},
                         "node_modules/lodash": {"version": "4.17.20"}},
        }), encoding="utf-8")
        output = invoke(["trivy", "fs", "--disable-telemetry", "--no-progress", "--scanners", "vuln", "--format", "json", str(npm)], (0,))
        data = json.loads(output)
        assert any(v.get("FixedVersion") for result in data.get("Results", []) for v in result.get("Vulnerabilities", [])), "Vulnerable dependency not detected"
    print("PASS: redacted secret, workflow injection, ESLint violation, vulnerable dependency")


if __name__ == "__main__":
    main()
