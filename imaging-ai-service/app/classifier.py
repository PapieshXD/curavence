import re

from app.models import Classification, SeriesClassification, StudyInput


_BRAIN_TERMS = (
    "brain", "head", "cran", "cerebr", "glowa", "głowa", "mozg", "mózg",
    "encephal",
)
_LOCALIZER_TERMS = ("localizer", "scout", "topogram", "survey", "locator")


def _text(*values: str | None) -> str:
    return " ".join(value.strip().lower() for value in values if value and value.strip())


def _contains(text: str, terms: tuple[str, ...]) -> bool:
    return any(term in text for term in terms)


def normalize_modality(value: str | None) -> str:
    normalized = (value or "").strip().upper()
    return "MR" if normalized in {"MR", "MRI"} else normalized


def classify_sequence(description: str | None, sequence_name: str | None) -> str:
    value = _text(description, sequence_name)
    if re.search(r"\badc\b", value):
        return "ADC"
    if re.search(r"\b(dwi|diff|tracew)\b", value):
        return "DWI"
    if "flair" in value or "dark fluid" in value:
        return "FLAIR"
    if re.search(r"\b(t1c|t1\+c|t1 post|t1 gad|mprage post)\b", value):
        return "T1C"
    if re.search(r"\b(t1|mprage|spgr)\b", value):
        return "T1"
    if re.search(r"\b(t2|space|cube)\b", value):
        return "T2"
    return "UNKNOWN"


def classify_plane(description: str | None) -> str:
    value = _text(description)
    if re.search(r"\b(ax|axi|axial|tra|trans)\b", value):
        return "AXIAL"
    if re.search(r"\b(cor|coronal)\b", value):
        return "CORONAL"
    if re.search(r"\b(sag|sagittal)\b", value):
        return "SAGITTAL"
    return "UNKNOWN"


def _series_score(series, modality: str) -> tuple[int, int]:
    description = _text(series.description, series.sequence_name)
    score = min(series.image_count, 500)
    if _contains(description, _LOCALIZER_TERMS):
        score -= 1000
    if classify_plane(description) == "AXIAL":
        score += 180
    if modality == "CT":
        if _contains(description, ("head", "brain", "soft", "standard", "axial")):
            score += 120
        if _contains(description, ("bone", "3d", "dose", "report")):
            score -= 80
    else:
        priority = {"T1": 180, "T1C": 170, "FLAIR": 160, "T2": 150, "DWI": 50, "ADC": 40}
        score += priority.get(classify_sequence(series.description, series.sequence_name), 0)
    return score, series.image_count


def classify_study(study: StudyInput) -> Classification:
    modality = normalize_modality(study.modality)
    metadata_text = _text(study.body_part_examined, study.study_description,
                          *(series.description for series in study.series))
    anatomy = "BRAIN" if _contains(metadata_text, _BRAIN_TERMS) else "UNKNOWN"
    source = "DICOM_METADATA" if modality in {"CT", "MR"} and anatomy != "UNKNOWN" else "UNKNOWN"
    pipeline = {("CT", "BRAIN"): "CT_HEAD_V1", ("MR", "BRAIN"): "MR_BRAIN_V1"}.get(
        (modality, anatomy), "UNSUPPORTED"
    )

    eligible = [
        series for series in study.series
        if normalize_modality(series.modality or modality) == modality and series.image_count > 0
        and not _contains(_text(series.description, series.sequence_name), _LOCALIZER_TERMS)
    ]
    selected_series = sorted(eligible, key=lambda item: _series_score(item, modality), reverse=True)
    selected = selected_series[0] if selected_series else None
    series_classifications = [
        SeriesClassification(
            series_instance_uid=series.series_instance_uid,
            plane=classify_plane(series.description),
            sequence="CT" if modality == "CT" else classify_sequence(series.description, series.sequence_name),
            image_count=series.image_count,
            confidence=0.9 if series is selected else 0.8,
        )
        for series in selected_series
    ]
    confidence = 0.95 if pipeline != "UNSUPPORTED" and selected else 0.55 if pipeline != "UNSUPPORTED" else 0.0
    return Classification(
        modality=modality or "UNKNOWN",
        anatomy=anatomy,
        pipeline=pipeline,
        confidence=confidence,
        source=source,
        selected_series_instance_uid=selected.series_instance_uid if selected else None,
        selected_series_instance_uids=[series.series_instance_uid for series in selected_series],
        selected_series=series_classifications,
    )
