from pathlib import Path

import numpy as np
import pydicom

from app.models import QualityResult, SeriesQualityResult


def assess_quality(dicom_dir: Path) -> QualityResult:
    files = sorted(path for path in dicom_dir.iterdir() if path.is_file())
    warnings: list[str] = []
    if not files:
        return QualityResult(status="requires_review", warnings=["Brak obrazów DICOM w wybranej serii."])

    sample_indices = np.linspace(0, len(files) - 1, min(9, len(files)), dtype=int)
    normalized_slices: list[np.ndarray] = []
    readable = 0
    for index in sample_indices:
        try:
            dataset = pydicom.dcmread(files[index], force=True)
            pixels = dataset.pixel_array.astype(np.float32)
            if pixels.ndim > 2:
                pixels = pixels.reshape((-1, *pixels.shape[-2:]))[0]
            low, high = np.percentile(pixels, [1, 99])
            if high <= low:
                continue
            normalized_slices.append(np.clip((pixels - low) / (high - low), 0, 1))
            readable += 1
        except Exception:
            continue

    if readable == 0:
        return QualityResult(
            status="requires_review",
            assessed_slices=0,
            total_slices=len(files),
            coverage=min(1.0, len(files) / 32),
            warnings=["Nie udało się odczytać pikseli do automatycznej kontroli jakości."],
        )

    noise_values = []
    for pixels in normalized_slices:
        residual = pixels - (
            np.roll(pixels, 1, axis=0) + np.roll(pixels, -1, axis=0)
            + np.roll(pixels, 1, axis=1) + np.roll(pixels, -1, axis=1)
        ) / 4
        noise_values.append(float(np.median(np.abs(residual))))
    noise = float(np.clip(np.median(noise_values) * 5, 0, 1))

    motion_values = []
    for left, right in zip(normalized_slices, normalized_slices[1:]):
        if left.shape != right.shape:
            continue
        correlation = np.corrcoef(left.ravel(), right.ravel())[0, 1]
        if np.isfinite(correlation):
            motion_values.append(1 - abs(float(correlation)))
    motion = float(np.clip(np.median(motion_values), 0, 1)) if motion_values else None
    coverage = min(1.0, len(files) / 32)
    if len(files) < 16:
        warnings.append("Seria ma mniej niż 16 przekrojów; pokrycie anatomii może być niepełne.")
    if noise > 0.45:
        warnings.append("Heurystyka wykryła podwyższony poziom szumu.")
    if motion is not None and motion > 0.65:
        warnings.append("Heurystyka wykryła możliwe artefakty ruchowe lub duży odstęp między przekrojami.")
    return QualityResult(
        status="acceptable" if not warnings else "requires_review",
        assessed_slices=readable,
        total_slices=len(files),
        coverage=round(coverage, 4),
        noise=round(noise, 4),
        motion=round(motion, 4) if motion is not None else None,
        warnings=warnings,
    )


def aggregate_quality(results: list[tuple[str, QualityResult]]) -> QualityResult:
    """Aggregate per-series QC without hiding a weak series behind a good one."""
    if not results:
        return QualityResult(status="not_assessed")

    total_slices = sum(result.total_slices for _, result in results)
    assessed_slices = sum(result.assessed_slices for _, result in results)

    def weighted(field: str) -> float | None:
        values = [
            (getattr(result, field), max(result.total_slices, 1))
            for _, result in results
            if getattr(result, field) is not None
        ]
        if not values:
            return None
        return round(sum(value * weight for value, weight in values) / sum(weight for _, weight in values), 4)

    warnings = [
        f"Materiał {index}: {warning}"
        for index, (_, result) in enumerate(results, start=1)
        for warning in result.warnings
    ]
    statuses = {result.status for _, result in results}
    status = "requires_review" if "requires_review" in statuses else (
        "acceptable" if statuses == {"acceptable"} else "not_assessed"
    )
    return QualityResult(
        status=status,
        series_count=len(results),
        assessed_slices=assessed_slices,
        total_slices=total_slices,
        coverage=weighted("coverage"),
        noise=weighted("noise"),
        motion=weighted("motion"),
        warnings=warnings,
        series=[
            SeriesQualityResult(
                series_instance_uid=series_uid,
                status=result.status,
                assessed_slices=result.assessed_slices,
                total_slices=result.total_slices,
                coverage=result.coverage,
                noise=result.noise,
                motion=result.motion,
                warnings=result.warnings,
            )
            for series_uid, result in results
        ],
    )
