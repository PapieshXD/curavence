import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from app.classifier import classify_study, _contains, _text, _LOCALIZER_TERMS
from app.explanations import explain, evidence_hash
from app.config import Settings
from app.engine import SegmentationEngine
from app.models import AnalysisRequest, AnalysisResponse, QualityResult
from app.orthanc import OrthancSource, flatten_dicom_files
from app.qc import aggregate_quality, assess_quality
from app.summary import EvidenceSummarizer


class AnalysisService:
    def __init__(self, settings: Settings, engine: SegmentationEngine,
                 summarizer: EvidenceSummarizer | None = None,
                 source_factory: Callable[[Settings], OrthancSource] | None = None):
        self._settings = settings
        self._engine = engine
        self._summarizer = summarizer or EvidenceSummarizer(settings)
        self._source_factory = source_factory or OrthancSource

    def analyze(self, request: AnalysisRequest) -> AnalysisResponse:
        classification = classify_study(request.study)
        if request.options.scope_mode == "SELECTED_SERIES":
            wanted = set(request.options.series_instance_uids)
            classification.selected_series = [s for s in classification.selected_series if s.series_instance_uid in wanted]
            classification.selected_series_instance_uids = [s.series_instance_uid for s in classification.selected_series]
            classification.selected_series_instance_uid = next(iter(classification.selected_series_instance_uids), None)
        selected = set(classification.selected_series_instance_uids)
        series_results = [{
            "seriesInstanceUid": series.series_instance_uid,
            "role": "TECHNICAL" if _contains(_text(series.description, series.sequence_name), _LOCALIZER_TERMS) else "DIAGNOSTIC",
            "analysisStatus": "ELIGIBLE" if series.series_instance_uid in selected else (
                "EXCLUDED_TECHNICAL" if _contains(_text(series.description, series.sequence_name), _LOCALIZER_TERMS) else "EXCLUDED_BY_SCOPE" if request.options.scope_mode == "SELECTED_SERIES" and series.series_instance_uid not in request.options.series_instance_uids else "UNSUPPORTED"),
        } for series in request.study.series]
        scope = {"selectionMode": request.options.scope_mode, "includedSeries": classification.selected_series_instance_uids,
                 "excludedSeries": [s.series_instance_uid for s in request.study.series if s.series_instance_uid not in selected]}
        completed = 0
        if classification.pipeline == "UNSUPPORTED" or not selected or request.study.storage_provider.upper() != "ORTHANC":
            for item in series_results:
                if item["analysisStatus"] == "ELIGIBLE":
                    item["analysisStatus"] = "UNSUPPORTED"
            quality = QualityResult(status="not_assessed")
            evidence = {"scope": scope, "seriesResults": series_results,
                        "quality": quality.model_dump(mode="json", by_alias=True),
                        "classification": classification.model_dump(mode="json", by_alias=True),
                        "measurements": [], "observations": [], "candidateFindings": [],
                        "segmentationArtifacts": [], "modelVersions": {}}
            return AnalysisResponse(analysis_id=request.analysis_id, status="UNSUPPORTED",
                pipeline=classification.pipeline, engine=self._engine.name, classification=classification,
                quality=quality, scope=scope, series_results=series_results, canonical_evidence=evidence,
                evidence_hash=evidence_hash(evidence), explanations=explain(evidence, 0, len(selected)),
                summary="Nie można bezpiecznie uruchomić analizy dla tego materiału. Obrazy wymagają ręcznej oceny.",
                warnings=["Obsługiwane są wyłącznie zakwalifikowane obrazy MRI mózgu i TK głowy w magazynie Orthanc."],
                completed_at=datetime.now(timezone.utc))
        work_dir = self._settings.data_dir / request.analysis_id
        if work_dir.exists():
            shutil.rmtree(work_dir)
        input_dir = work_dir / "input"
        output_dir = work_dir / "output"
        input_dir.mkdir(parents=True)
        output_dir.mkdir(parents=True)
        try:
            quality_results = []
            measurements = []
            observations = []
            findings = []
            artifacts = []
            versions = {"classifier": "dicom-metadata-v1", "qc": "metadata-and-pixel-heuristics-v1"}
            engine_warnings = []

            with self._source_factory(self._settings) as source:
                for index, series in enumerate(classification.selected_series, start=1):
                    series_input = input_dir / f"{index:03d}"
                    series_output = output_dir / f"{index:03d}"
                    series_input.mkdir(parents=True)
                    series_output.mkdir(parents=True)
                    try:
                        extracted = source.download_series(
                            request.study.study_instance_uid,
                            series.series_instance_uid,
                            series_input,
                        )
                        dicom_dir = flatten_dicom_files(extracted, series_input / "flat")
                        series_quality = assess_quality(dicom_dir)
                        output = self._engine.run(
                            request.analysis_id,
                            dicom_dir,
                            series_output,
                            classification,
                            series,
                            request.options.run_pathology_models,
                        )
                        measurements.extend(output.measurements)
                        observations.extend(output.observations)
                        findings.extend(output.findings)
                        artifacts.extend(output.artifacts)
                        engine_warnings.extend(
                            f"Materiał {index}: {warning}"
                            for warning in output.warnings
                        )
                        for name, version in output.versions.items():
                            versions[f"series-{index}:{name}"] = version
                        quality_results.append((series.series_instance_uid, series_quality))
                        completed += 1
                        next(r for r in series_results if r["seriesInstanceUid"] == series.series_instance_uid)["analysisStatus"] = "PARTIALLY_ANALYZED" if output.warnings else "ANALYZED"
                    except Exception:
                        # A failed series must not erase successful results or expose source identifiers.
                        next(r for r in series_results if r["seriesInstanceUid"] == series.series_instance_uid)["analysisStatus"] = "FAILED"
                        engine_warnings.append(f"Nie udało się przeanalizować materiału {index}. Obrazy pozostają dostępne do ręcznej oceny.")

            quality = aggregate_quality(quality_results)
            if completed < len(selected):
                quality.status = "requires_review"
                quality.warnings.append("Analiza nie obejmuje całego zakwalifikowanego materiału.")
            finding_count = len(findings) + len(observations)
            fallback_summary = (
                f"Automatycznie przeanalizowano łącznie {quality.total_slices} obrazów ze wszystkich "
                f"{quality.series_count} zakwalifikowanych serii w pipeline {classification.pipeline}. "
                "Każdą serię przetworzono osobno i przygotowano segmentacje oraz pomiary. "
                f"Liczba obszarów wymagających uwagi: {finding_count}. "
                "Wynik wymaga oceny specjalisty."
            )
            for index, finding in enumerate(findings, start=1):
                finding.id = f"finding-{index}"
            evidence = {
                "quality": quality.model_dump(mode="json", by_alias=True),
                "classification": classification.model_dump(mode="json", by_alias=True),
                "measurements": [value.model_dump(mode="json", by_alias=True) for value in measurements],
                "observations": [value.model_dump(mode="json", by_alias=True) for value in observations],
                "candidateFindings": [value.model_dump(mode="json", by_alias=True) for value in findings],
            }
            evidence["scope"] = scope
            evidence["seriesResults"] = series_results
            evidence["modelVersions"] = dict(versions)
            evidence["segmentationArtifacts"] = [a.model_dump(mode="json", by_alias=True) for a in artifacts]
            if completed < len(selected):
                fallback_summary = f"Przeanalizowano {completed} z {len(selected)} zakwalifikowanych serii. Wynik jest niepełny i wymaga oceny specjalisty."
            # Audience text is generated only from validated structured evidence.
            summary, summary_model = fallback_summary, "evidence-templates-v1"
            versions["summary"] = summary_model
            return AnalysisResponse(
                analysis_id=request.analysis_id,
                status="SUCCEEDED" if completed == len(selected) and not engine_warnings else "DEGRADED" if completed else "FAILED",
                scope=scope, series_results=series_results, canonical_evidence=evidence,
                evidence_hash=evidence_hash(evidence),
                explanations=explain(evidence, completed, len(selected)),
                pipeline=classification.pipeline,
                engine=self._engine.name,
                classification=classification,
                quality=quality,
                measurements=measurements,
                observations=observations,
                candidate_findings=findings,
                artifacts=artifacts,
                summary=summary,
                model_versions=versions,
                warnings=[*quality.warnings, *engine_warnings],
                completed_at=datetime.now(timezone.utc),
            )
        finally:
            if not self._settings.keep_work_directory:
                shutil.rmtree(input_dir, ignore_errors=True)

    def artifact_path(self, analysis_id: str, artifact_id: str) -> Path:
        if any(value in {"", ".", ".."} or "/" in value or "\\" in value for value in (analysis_id, artifact_id)):
            raise FileNotFoundError
        output = (self._settings.data_dir / analysis_id / "output").resolve()
        for suffix in (".zip", ".json", ".nii.gz", ".dcm"):
            matches = [candidate.resolve() for candidate in output.rglob(f"{artifact_id}{suffix}")]
            safe_matches = [candidate for candidate in matches if output in candidate.parents and candidate.is_file()]
            if len(safe_matches) == 1:
                return safe_matches[0]
        raise FileNotFoundError
