"""Compare PR dependency findings with the base revision; full scans gate all findings."""
import argparse
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

from quality import ROOT, gate


def run(command, **kwargs):
    subprocess.run(command, check=True, **kwargs)


def scan(target, report, installed=False):
    mode = "rootfs" if installed else "fs"
    run(["trivy", mode, "--disable-telemetry", "--no-progress", "--scanners", "vuln", "--include-dev-deps", "--format", "json",
         "--output", str(report), str(target)])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("module", choices=["frontend", "backend", "python"])
    args = parser.parse_args()
    reports = ROOT / "reports"
    reports.mkdir(exist_ok=True)
    current = reports / f"trivy-{args.module}.json"
    if args.module == "frontend":
        target = ROOT / "frontend/package-lock.json"
    elif args.module == "backend":
        target = ROOT / "backend/target/backend-0.0.1-SNAPSHOT.jar"
    else:
        run([sys.executable, str(ROOT / ".github/scripts/python_inventory.py")])
        target = reports / "python-inventory"
    # fs intentionally disables JAR and installed-package analyzers; rootfs enables them.
    scan(target, current, installed=args.module != "frontend")
    base = os.environ.get("BASE_SHA", "")
    if not base:
        return gate("trivy", current, strict=True)
    subprocess.run(["git", "rev-parse", "--verify", f"{base}^{{commit}}"], check=True, stdout=subprocess.DEVNULL)
    comparison = reports / f"trivy-{args.module}-base.json"
    with tempfile.TemporaryDirectory() as temporary:
        destination = Path(temporary)
        if args.module == "backend":
            archive = subprocess.check_output(["git", "archive", base, "backend"])
            with tarfile.open(fileobj=io.BytesIO(archive)) as source:
                source.extractall(destination, filter="data")
            run(["bash", "./mvnw", "-B", "-ntp", "package", "-DskipTests"], cwd=destination / "backend")
            jars = [path for path in (destination / "backend/target").glob("*.jar") if path.name.startswith("backend-")]
            if len(jars) != 1:
                raise RuntimeError("Expected one base backend JAR")
            target = jars[0]
        else:
            source = "frontend/package-lock.json" if args.module == "frontend" else "imaging-ai-service/requirements-ci.lock"
            exists = subprocess.run(["git", "cat-file", "-e", f"{base}:{source}"], stderr=subprocess.DEVNULL)
            if exists.returncode:
                # First adoption has no trusted reference: do not silently baseline vulnerabilities.
                return gate("trivy", current, strict=True)
            target = destination / ("package-lock.json" if args.module == "frontend" else "requirements.txt")
            target.write_bytes(subprocess.check_output(["git", "show", f"{base}:{source}"]))
        scan(target, comparison, installed=args.module == "backend")
    return gate("trivy", current, comparison=comparison)


if __name__ == "__main__":
    sys.exit(main())
