from importlib.metadata import version, PackageNotFoundError
import json
import re
import shutil
import subprocess
import uuid
from abc import ABC, abstractmethod
from pathlib import Path

from app.config import Settings
from app.models import (
    Artifact,
    CandidateFinding,
    Classification,
    Measurement,
    Observation,
    SeriesClassification,
)


class SegmentationOutput:
    def __init__(self, measurements=None, observations=None, findings=None, artifacts=None, versions=None, warnings=None):
        self.measurements = measurements or []
        self.observations = observations or []
        self.findings = findings or []
        self.artifacts = artifacts or []
        self.versions = versions or {}
        self.warnings = warnings or []


class SegmentationEngine(ABC):
    @property
    @abstractmethod
    def name(self) -> str: ...

    @abstractmethod
    def run(self, analysis_id: str, dicom_dir: Path, output_dir: Path,
            classification: Classification, series: SeriesClassification,
            run_pathology: bool) -> SegmentationOutput: ...


class TotalSegmentatorEngine(SegmentationEngine):
    def __init__(self, settings: Settings):
        self._settings = settings

    @property
    def name(self) -> str:
        return "totalsegmentator"

    def run(self, analysis_id: str, dicom_dir: Path, output_dir: Path,
            classification: Classification, series: SeriesClassification,
            run_pathology: bool) -> SegmentationOutput:
        anatomy_dir = output_dir / "anatomy"
        task = "total_mr" if classification.modality == "MR" else "total"
        command = [
            "TotalSegmentator", "-i", str(dicom_dir), "-o", str(anatomy_dir),
            "--task", task, "--statistics", "--device", self._settings.device,
            "--nr_thr_saving", "1",
        ]
        if self._settings.fast:
            command.append("--fast")
        self._execute(command)

        measurements = self._measurements(
            anatomy_dir / "statistics.json", classification.modality, series
        )
        series_label = self._series_label(series)
        artifacts = [self._zip_artifact(
            analysis_id, output_dir, anatomy_dir, "ANATOMY_MASKS",
            f"Maski segmentacji anatomii · {series_label}", series,
        )]
        findings: list[CandidateFinding] = []
        observations: list[Observation] = []
        warnings: list[str] = []
        try:
            package_version = version("TotalSegmentator")
        except PackageNotFoundError:
            package_version = "unknown"
        versions = {"anatomy": f"TotalSegmentator:{package_version}:{task}"}

        if run_pathology and classification.pipeline == "CT_HEAD_V1":
            bleed_dir = output_dir / "cerebral_bleed"
            try:
                self._execute([
                    "TotalSegmentator", "-i", str(dicom_dir), "-o", str(bleed_dir),
                    "--task", "cerebral_bleed", "--statistics", "--device", self._settings.device,
                    "--nr_thr_saving", "1",
                ])
                bleed_artifact = self._zip_artifact(
                    analysis_id, output_dir, bleed_dir, "CANDIDATE_MASK",
                    f"Automatycznie wykryty obszar wymagający uwagi · {series_label}",
                    series,
                )
                artifacts.append(bleed_artifact)
                versions["pathology"] = f"TotalSegmentator:{package_version}:cerebral_bleed"
                bleed_stats = self._read_statistics(bleed_dir / "statistics.json")
                bleed = bleed_stats.get("intracerebral_hemorrhage", {})
                volume_ml = self._number(bleed.get("volume"))
                if volume_ml is not None:
                    volume_ml /= 1000
                if volume_ml and volume_ml > 0:
                    findings.append(CandidateFinding(
                        type="possible_intracerebral_hemorrhage",
                        region="brain",
                        series_instance_uid=series.series_instance_uid,
                        sequence=series.sequence,
                        confidence=None,
                        volume_ml=round(volume_ml, 4),
                        artifact_id=bleed_artifact.id,
                    ))
            except RuntimeError as exception:
                warnings.append("Dedykowany model cerebral_bleed nie zakończył pracy. Wynik jest częściowy.")

        observations.extend(self._asymmetry_observations(measurements))
        return SegmentationOutput(
            measurements=measurements,
            observations=observations,
            findings=findings,
            artifacts=artifacts,
            versions=versions,
            warnings=warnings,
        )

    def _execute(self, command: list[str]) -> None:
        try:
            result = subprocess.run(
                command, capture_output=True, text=True, timeout=self._settings.command_timeout_seconds,
                check=False,
            )
        except FileNotFoundError as exception:
            raise RuntimeError("Polecenie TotalSegmentator nie jest zainstalowane w workerze") from exception
        except subprocess.TimeoutExpired as exception:
            raise RuntimeError("Segmentacja przekroczyła limit czasu") from exception
        if result.returncode != 0:
            detail = (result.stderr or result.stdout or "unknown error").strip()[-1500:]
            raise RuntimeError(f"TotalSegmentator zakończył się kodem {result.returncode}: {detail}")

    def _measurements(self, path: Path, modality: str,
                      series: SeriesClassification) -> list[Measurement]:
        statistics = self._read_statistics(path)
        measurements = []
        for region, values in statistics.items():
            if not isinstance(values, dict):
                continue
            volume = self._number(values.get("volume"))
            mean = self._number(values.get("intensity"))
            measurements.append(Measurement(
                region=region,
                series_instance_uid=series.series_instance_uid,
                sequence=series.sequence,
                volume_ml=round(volume / 1000, 4) if volume is not None else None,
                mean_intensity=round(mean, 4) if mean is not None else None,
                intensity_unit="HU" if modality == "CT" else "arbitrary_unit",
            ))
        self._attach_asymmetry(measurements)
        return measurements

    @staticmethod
    def _attach_asymmetry(measurements: list[Measurement]) -> None:
        indexed = {measurement.region: measurement for measurement in measurements}
        for measurement in measurements:
            if measurement.region.endswith("_left"):
                pair_name = measurement.region[:-5] + "_right"
            elif measurement.region.endswith("_right"):
                pair_name = measurement.region[:-6] + "_left"
            else:
                continue
            pair = indexed.get(pair_name)
            if pair and measurement.volume_ml is not None and pair.volume_ml is not None:
                denominator = max(measurement.volume_ml, pair.volume_ml, 0.001)
                measurement.paired_region = pair_name
                measurement.asymmetry = round(abs(measurement.volume_ml - pair.volume_ml) / denominator, 4)

    @staticmethod
    def _asymmetry_observations(measurements: list[Measurement]) -> list[Observation]:
        return [
            Observation(
                type="volume_asymmetry",
                region=measurement.region,
                series_instance_uid=measurement.series_instance_uid,
                sequence=measurement.sequence,
                confidence=None,
                evidence={
                    "pairedRegion": measurement.paired_region,
                    "relativeDifference": measurement.asymmetry,
                },
            )
            for measurement in measurements
            if measurement.asymmetry is not None and measurement.asymmetry >= 0.25
        ]

    @staticmethod
    def _read_statistics(path: Path) -> dict:
        if not path.exists():
            return {}
        with path.open(encoding="utf-8") as source:
            value = json.load(source)
            return value if isinstance(value, dict) else {}

    @staticmethod
    def _number(value):
        try:
            return float(value) if value is not None else None
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _zip_artifact(analysis_id: str, output_dir: Path, source: Path, kind: str,
                      label: str, series: SeriesClassification) -> Artifact:
        token = str(uuid.uuid4())
        suffix = re.sub(r"[^a-z0-9]+", "-", series.sequence.lower()).strip("-") or "series"
        uid_suffix = re.sub(r"[^0-9a-z]+", "", series.series_instance_uid.lower())[-10:]
        filename = f"{kind.lower()}-{suffix}-{uid_suffix}.zip"
        destination = output_dir / f"{token}.zip"
        shutil.make_archive(str(destination.with_suffix("")), "zip", source)
        return Artifact(
            id=token,
            kind=kind,
            label=label,
            filename=filename,
            mime_type="application/zip",
            size_bytes=destination.stat().st_size,
            download_path=f"/v1/analyses/{analysis_id}/artifacts/{token}",
            series_instance_uid=series.series_instance_uid,
        )

    @staticmethod
    def _series_label(series: SeriesClassification) -> str:
        parts = [value for value in (series.sequence, series.plane) if value and value != "UNKNOWN"]
        return " / ".join(parts) or f"seria {series.series_instance_uid[-8:]}"


class MockSegmentationEngine(SegmentationEngine):
    @property
    def name(self) -> str:
        return "mock-no-medical-inference"

    def run(self, analysis_id: str, dicom_dir: Path, output_dir: Path,
            classification: Classification, series: SeriesClassification,
            run_pathology: bool) -> SegmentationOutput:
        token = str(uuid.uuid4())
        filename = output_dir / f"{token}.json"
        filename.write_text(json.dumps({
            "analysisId": analysis_id,
            "pipeline": classification.pipeline,
            "seriesInstanceUid": series.series_instance_uid,
            "imageCount": len(list(dicom_dir.iterdir())),
            "notice": "Development mock: no segmentation model was executed.",
        }, indent=2), encoding="utf-8")
        artifact = Artifact(
            id=token,
            kind="DEVELOPMENT_MANIFEST",
            label=f"Manifest trybu testowego · {series.sequence}",
            filename=f"development-manifest-{series.sequence.lower()}.json",
            mime_type="application/json",
            size_bytes=filename.stat().st_size,
            download_path=f"/v1/analyses/{analysis_id}/artifacts/{token}",
            series_instance_uid=series.series_instance_uid,
        )
        return SegmentationOutput(
            artifacts=[artifact],
            versions={"engine": self.name},
            warnings=["Tryb developerski: nie uruchomiono modelu segmentacyjnego."],
        )


def create_engine(settings: Settings) -> SegmentationEngine:
    if settings.engine.lower() == "mock":
        return MockSegmentationEngine()
    if settings.engine.lower() == "totalsegmentator":
        return TotalSegmentatorEngine(settings)
    raise ValueError(f"Unsupported segmentation engine: {settings.engine}")
