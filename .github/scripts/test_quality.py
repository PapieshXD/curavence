"""Regression tests for the security gate, not for application implementation."""
from datetime import date
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import quality


class GateTests(unittest.TestCase):
    def setUp(self):
        summary = patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": ""})
        summary.start()
        self.addCleanup(summary.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.baseline = self.root / "baseline.json"
        self.exceptions = self.root / "exceptions.json"
        self.report = self.root / "report.json"
        self.baseline.write_text("{}", encoding="utf-8")
        self.exceptions.write_text("[]", encoding="utf-8")

    def test_baseline_does_not_hide_new_or_duplicate_findings(self):
        self.report.write_text('<BugCollection><BugInstance type="A" instanceHash="one"/></BugCollection>', encoding="utf-8")
        quality.gate("spotbugs", self.report, self.baseline, self.exceptions, record=True)
        self.assertEqual(quality.gate("spotbugs", self.report, self.baseline, self.exceptions), 0)
        self.report.write_text('<BugCollection><BugInstance type="A" instanceHash="one"/><BugInstance type="A" instanceHash="one"/></BugCollection>', encoding="utf-8")
        self.assertEqual(quality.gate("spotbugs", self.report, self.baseline, self.exceptions), 1)

    def test_expired_or_overlong_exceptions_fail(self):
        for expires in ("2026-01-02", "2026-03-01"):
            self.exceptions.write_text(json.dumps([dict(id="CVE-test", tool="trivy", reason="test", owner="team", created="2026-01-01", expires=expires)]), encoding="utf-8")
            with self.assertRaises(ValueError):
                quality.exceptions(self.exceptions, date(2026, 1, 10))

    def test_semgrep_errors_fail_closed(self):
        self.report.write_text('{"results": [], "errors": [{"message":"parse failed"}]}', encoding="utf-8")
        with self.assertRaises(ValueError):
            quality.gate("semgrep", self.report, self.baseline, self.exceptions)

    def test_invalid_and_empty_scanner_output_fail(self):
        for content in ("", "{}"):
            self.report.write_text(content, encoding="utf-8")
            with self.assertRaises(ValueError):
                quality.gate("trivy", self.report, self.baseline, self.exceptions)

    def test_fixed_high_vulnerability_blocks_and_cannot_be_baselined(self):
        self.report.write_text(json.dumps({"SchemaVersion": 2, "Results": [{"Packages": [{"Name": "test"}], "Vulnerabilities": [{"VulnerabilityID": "CVE-test", "PkgName": "test", "InstalledVersion": "1", "Severity": "HIGH", "FixedVersion": "2"}]}]}), encoding="utf-8")
        self.assertEqual(quality.gate("trivy", self.report, self.baseline, self.exceptions), 1)
        with self.assertRaises(ValueError):
            quality.gate("trivy", self.report, self.baseline, self.exceptions, record=True)

    def test_pr_comparison_does_not_bypass_release_gate(self):
        vulnerability = {"VulnerabilityID": "CVE-test", "PkgName": "test", "InstalledVersion": "1", "Severity": "HIGH", "FixedVersion": "2"}
        data = {"SchemaVersion": 2, "Results": [{"Packages": [{"Name": "test"}], "Vulnerabilities": [vulnerability]}]}
        self.report.write_text(json.dumps(data), encoding="utf-8")
        comparison = self.root / "base.json"
        comparison.write_text(json.dumps(data), encoding="utf-8")
        self.assertEqual(quality.gate("trivy", self.report, self.baseline, self.exceptions, comparison=comparison), 0)
        self.assertEqual(quality.gate("trivy", self.report, self.baseline, self.exceptions, comparison=comparison, strict=True), 1)
        vulnerability["InstalledVersion"] = "1.1"
        self.report.write_text(json.dumps(data), encoding="utf-8")
        self.assertEqual(quality.gate("trivy", self.report, self.baseline, self.exceptions, comparison=comparison), 1)

    def test_report_without_package_inventory_is_not_success(self):
        self.report.write_text('{"SchemaVersion": 2, "Results": []}', encoding="utf-8")
        with self.assertRaises(ValueError):
            quality.gate("trivy", self.report, self.baseline, self.exceptions)

    def test_eslint_parser_error_cannot_be_baselined(self):
        self.report.write_text('[{"filePath":"src/test.ts","messages":[{"fatal":true,"ruleId":null,"severity":2}]}]', encoding="utf-8")
        with self.assertRaises(ValueError):
            quality.gate("eslint", self.report, self.baseline, self.exceptions, record=True)


if __name__ == "__main__":
    unittest.main()
