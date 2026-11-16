"""Copy only installed package metadata for Trivy's Python package analyzer."""
from importlib import metadata
from pathlib import Path

target = Path("reports/python-inventory")
target.mkdir(parents=True, exist_ok=True)
for distribution in metadata.distributions():
    name = distribution.metadata["Name"].replace("-", "_")
    destination = target / f"{name}-{distribution.version}.dist-info"
    destination.mkdir(exist_ok=True)
    (destination / "METADATA").write_text(distribution.read_text("METADATA") or "", encoding="utf-8")
