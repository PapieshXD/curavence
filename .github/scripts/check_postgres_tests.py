"""Fail if any PostgreSQL test class is missing, skipped, or failed."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("backend")
sources = list((root / "src/test/java").rglob("*PostgresTest.java"))
assert sources, "No PostgreSQL tests found"
for source in sources:
    matches = list((root / "target/surefire-reports").glob(f"TEST-*.{source.stem}.xml"))
    assert len(matches) == 1, f"Missing report for {source.stem}"
    suite = ET.parse(matches[0]).getroot()
    assert int(suite.get("tests", "0")) > 0, f"No executed tests: {source.stem}"
    for attribute in ("skipped", "errors", "failures"):
        assert int(suite.get(attribute, "0")) == 0, f"{source.stem}: {attribute}"
    print(f"{source.stem}: {suite.get('tests')} tests executed")
