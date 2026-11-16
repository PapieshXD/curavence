from pathlib import Path

from app.config import Settings
from app.engine import SegmentationEngine, SegmentationOutput
from app.models import AnalysisRequest, SeriesInput, StudyInput
from app.service import AnalysisService


class FakeSource:
    downloaded: list[str] = []

    def __init__(self, _settings):
        self.downloaded = []
        FakeSource.downloaded = self.downloaded

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def download_series(self, _study_uid: str, series_uid: str, target: Path) -> Path:
        self.downloaded.append(series_uid)
        dicom_dir = target / "dicom"
        dicom_dir.mkdir(parents=True)
        count = {"flair": 3, "dwi": 2}[series_uid]
        for index in range(count):
            (dicom_dir / f"{index}.dcm").write_bytes(b"not-a-real-dicom")
        return dicom_dir


class RecordingEngine(SegmentationEngine):
    def __init__(self):
        self.calls: list[tuple[str, int]] = []

    @property
    def name(self) -> str:
        return "recording-engine"

    def run(self, _analysis_id, dicom_dir, _output_dir, _classification, series, _run_pathology):
        self.calls.append((series.series_instance_uid, len(list(dicom_dir.iterdir()))))
        return SegmentationOutput(versions={"engine": self.name})


class StableSummarizer:
    def summarize(self, _evidence: dict, fallback: str):
        return fallback, "test-template"


def test_analysis_processes_every_non_localizer_series_and_every_image(tmp_path):
    engine = RecordingEngine()
    service = AnalysisService(
        Settings(data_dir=tmp_path, keep_work_directory=True),
        engine,
        StableSummarizer(),
        FakeSource,
    )
    request = AnalysisRequest(
        analysis_id="11111111-2222-3333-4444-555555555555",
        study=StudyInput(
            study_instance_uid="1.2.3",
            storage_provider="ORTHANC",
            modality="MR",
            study_description="MRI BRAIN",
            series=[
                SeriesInput(series_instance_uid="localizer", description="SCOUT", image_count=1),
                SeriesInput(series_instance_uid="dwi", description="AX DWI", image_count=2),
                SeriesInput(series_instance_uid="flair", description="AX T2 FLAIR", image_count=3),
            ],
        ),
    )

    result = service.analyze(request)

    assert result.status == "SUCCEEDED"
    assert FakeSource.downloaded == ["flair", "dwi"]
    assert engine.calls == [("flair", 3), ("dwi", 2)]
    assert result.classification.selected_series_instance_uids == ["flair", "dwi"]
    assert result.quality.series_count == 2
    assert result.quality.total_slices == 5
    assert "5 obrazów ze wszystkich 2" in result.summary


def request_for_test():
    return AnalysisRequest(analysis_id="partial-test", study=StudyInput(
        study_instance_uid="1.2.3", storage_provider="ORTHANC", modality="MR",
        study_description="MRI BRAIN", series=[
            SeriesInput(series_instance_uid="flair", description="FLAIR", image_count=3),
            SeriesInput(series_instance_uid="dwi", description="DWI", image_count=2),
            SeriesInput(series_instance_uid="scout", description="SCOUT", image_count=1),
        ]))


def test_failed_series_preserves_other_results_and_evidence(tmp_path):
    class PartialEngine(RecordingEngine):
        def run(self, *args):
            if args[4].series_instance_uid == "dwi":
                raise RuntimeError("private DICOM identifier must not escape")
            return super().run(*args)

    from app.explanations import evidence_hash
    result = AnalysisService(Settings(data_dir=tmp_path), PartialEngine(),
                             StableSummarizer(), FakeSource).analyze(request_for_test())
    assert result.status == "DEGRADED"
    assert result.quality.series_count == 1
    assert result.quality.status == "requires_review"
    assert {r["seriesInstanceUid"]: r["analysisStatus"] for r in result.series_results} == {
        "flair": "ANALYZED", "dwi": "FAILED", "scout": "EXCLUDED_TECHNICAL"}
    assert result.evidence_hash == evidence_hash(result.canonical_evidence)
    assert "private DICOM" not in result.model_dump_json()
    assert all("Przetworzono 1 z 2" in " ".join(e["limitations"]) for e in result.explanations.values())
    assert "confidence" not in str(result.explanations["PATIENT"])


def test_all_failed_does_not_claim_normal_result(tmp_path):
    class FailedEngine(RecordingEngine):
        def run(self, *args):
            raise RuntimeError("unavailable")

    result = AnalysisService(Settings(data_dir=tmp_path), FailedEngine(),
                             StableSummarizer(), FakeSource).analyze(request_for_test())
    assert result.status == "FAILED"
    assert result.measurements == []
    assert "Nie udało się ocenić" in result.explanations["PATIENT"]["whatWasSeen"]


def test_selected_scope_is_independent_of_technical_exclusion(tmp_path):
    request = request_for_test()
    request.options.scope_mode = "SELECTED_SERIES"
    request.options.series_instance_uids = ["dwi"]
    result = AnalysisService(Settings(data_dir=tmp_path), RecordingEngine(),
                             StableSummarizer(), FakeSource).analyze(request)
    assert FakeSource.downloaded == ["dwi"]
    assert result.scope["includedSeries"] == ["dwi"]
    assert next(r for r in result.series_results if r["seriesInstanceUid"] == "flair")["analysisStatus"] == "EXCLUDED_BY_SCOPE"
    assert next(r for r in result.series_results if r["seriesInstanceUid"] == "scout")["analysisStatus"] == "EXCLUDED_TECHNICAL"


def test_unsupported_study_keeps_inventory_and_safe_explanations(tmp_path):
    request = request_for_test()
    request.study.modality = "US"
    result = AnalysisService(Settings(data_dir=tmp_path), RecordingEngine(),
                             StableSummarizer(), FakeSource).analyze(request)
    assert result.status == "UNSUPPORTED"
    assert len(result.series_results) == 3
    assert result.evidence_hash
    assert "Nie udało się ocenić" in result.explanations["PATIENT"]["whatWasSeen"]


def test_audience_explanations_reuse_measurements_and_preserve_uncertainty():
    from app.explanations import explain, DISCLAIMER
    evidence = {"quality": {"status": "requires_review"},
                "measurements": [{"region": "brain", "volumeMl": 12.5}],
                "observations": [], "candidateFindings": [{"type": "candidate", "confidence": 0.4}]}
    result = explain(evidence, 1, 2)
    assert result["PHYSIOTHERAPY"]["measurements"] == result["CLINICIAN"]["measurements"] == evidence["measurements"]
    assert result["CLINICIAN"]["candidateFindings"] == evidence["candidateFindings"]
    assert "confidence" not in str(result["PATIENT"])
    assert "12.5" not in str(result["PATIENT"])
    assert all(DISCLAIMER in value["limitations"] for value in result.values())
