from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class SeriesInput(ApiModel):
    series_instance_uid: str
    modality: str | None = None
    description: str | None = None
    sequence_name: str | None = None
    image_count: int = 0


class StudyInput(ApiModel):
    study_instance_uid: str
    storage_provider: str
    modality: str | None = None
    body_part_examined: str | None = None
    study_description: str | None = None
    series: list[SeriesInput] = Field(default_factory=list)


class AnalysisOptions(ApiModel):
    fast: bool = True
    device: str = "cpu"
    run_pathology_models: bool = True
    scope_mode: Literal["ALL_ELIGIBLE", "SELECTED_SERIES", "REANALYZE"] = "ALL_ELIGIBLE"
    series_instance_uids: list[str] = Field(default_factory=list)


class AnalysisRequest(ApiModel):
    analysis_id: str
    study: StudyInput
    options: AnalysisOptions = Field(default_factory=AnalysisOptions)


class SeriesClassification(ApiModel):
    series_instance_uid: str
    plane: str
    sequence: str
    image_count: int = 0
    confidence: float = Field(ge=0, le=1)


class Classification(ApiModel):
    modality: str
    anatomy: str
    pipeline: str
    confidence: float = Field(ge=0, le=1)
    source: Literal["DICOM_METADATA", "IMAGE_FALLBACK", "UNKNOWN"]
    # The first UID is kept for backwards compatibility and identifies the
    # highest-priority series. Inference uses every UID from the plural field.
    selected_series_instance_uid: str | None = None
    selected_series_instance_uids: list[str] = Field(default_factory=list)
    selected_series: list[SeriesClassification] = Field(default_factory=list)


class SeriesQualityResult(ApiModel):
    series_instance_uid: str
    status: Literal["acceptable", "requires_review", "not_assessed"]
    assessed_slices: int = 0
    total_slices: int = 0
    coverage: float | None = None
    noise: float | None = None
    motion: float | None = None
    warnings: list[str] = Field(default_factory=list)


class QualityResult(ApiModel):
    status: Literal["acceptable", "requires_review", "not_assessed"]
    series_count: int = 0
    assessed_slices: int = 0
    total_slices: int = 0
    coverage: float | None = None
    noise: float | None = None
    motion: float | None = None
    warnings: list[str] = Field(default_factory=list)
    series: list[SeriesQualityResult] = Field(default_factory=list)
    method: str = "metadata-and-pixel-heuristics-v1"


class Measurement(ApiModel):
    region: str
    series_instance_uid: str | None = None
    sequence: str | None = None
    volume_ml: float | None = None
    mean_intensity: float | None = None
    intensity_unit: str | None = None
    paired_region: str | None = None
    asymmetry: float | None = None
    status: str = "measured"


class Observation(ApiModel):
    type: str
    region: str | None = None
    series_instance_uid: str | None = None
    sequence: str | None = None
    confidence: float | None = None
    status: str = "requires_review"
    evidence: dict[str, Any] = Field(default_factory=dict)


class CandidateFinding(ApiModel):
    id: str | None = None
    type: str
    region: str | None = None
    series_instance_uid: str | None = None
    sequence: str | None = None
    confidence: float | None = None
    volume_ml: float | None = None
    slice_index: int | None = None
    artifact_id: str | None = None
    status: str = "requires_review"


class Artifact(ApiModel):
    id: str
    kind: str
    label: str
    filename: str
    mime_type: str
    size_bytes: int
    download_path: str
    series_instance_uid: str | None = None
    slice_index: int | None = None


class AnalysisResponse(ApiModel):
    analysis_id: str
    status: Literal["SUCCEEDED", "DEGRADED", "FAILED", "UNSUPPORTED"]
    scope: dict[str, Any] = Field(default_factory=dict)
    series_results: list[dict[str, Any]] = Field(default_factory=list)
    canonical_evidence: dict[str, Any] = Field(default_factory=dict)
    evidence_hash: str | None = None
    explanations: dict[str, Any] = Field(default_factory=dict)
    prompt_version: str = "evidence-templates-v1"
    progress: int = 100
    pipeline: str
    engine: str
    classification: Classification
    quality: QualityResult
    measurements: list[Measurement] = Field(default_factory=list)
    observations: list[Observation] = Field(default_factory=list)
    candidate_findings: list[CandidateFinding] = Field(default_factory=list)
    artifacts: list[Artifact] = Field(default_factory=list)
    summary: str
    model_versions: dict[str, str] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)
    completed_at: datetime
    disclaimer: str = (
        "Wynik automatyczny nie jest diagnozą i wymaga oceny wykwalifikowanego specjalisty."
    )
