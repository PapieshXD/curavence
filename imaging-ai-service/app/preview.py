"""Source-faithful DICOM contact sheets and NIfTI masks in DICOM pixel coordinates.

No intensity projections or generated anatomy are used. Every tile is a source frame.
"""
import hashlib
import json
import math
import re
import shutil
import threading
import zipfile
from pathlib import Path
from uuid import UUID

import nibabel as nib
import numpy as np
import pydicom
from PIL import Image, ImageDraw
from pydantic import BaseModel, Field
from pydicom.pixels import pixel_array

from app.config import Settings
from app.orthanc import OrthancSource


class PreviewMask(BaseModel):
    id: str
    kind: str


class PreviewRequest(BaseModel):
    studyInstanceUid: str = Field(pattern=r"^[0-9.]{1,64}$")
    seriesInstanceUid: str = Field(pattern=r"^[0-9.]{1,64}$")
    analysisId: str | None = None
    masks: list[PreviewMask] = Field(default_factory=list, max_length=16)


def frame_geometry(ds, index: int):
    """Return native row/column geometry in DICOM LPS, including enhanced DICOM."""
    per_frame = getattr(ds, "PerFrameFunctionalGroupsSequence", [])
    shared = getattr(ds, "SharedFunctionalGroupsSequence", [])
    groups = ([per_frame[index]] if index < len(per_frame) else []) + list(shared[:1])

    def value(sequence, attribute):
        for group in groups:
            items = getattr(group, sequence, [])
            if items and hasattr(items[0], attribute):
                return np.asarray(getattr(items[0], attribute), dtype=float)
        original = getattr(ds, attribute, None)
        return np.asarray(original, dtype=float) if original is not None else None

    origin = value("PlanePositionSequence", "ImagePositionPatient")
    orientation = value("PlaneOrientationSequence", "ImageOrientationPatient")
    spacing = value("PixelMeasuresSequence", "PixelSpacing")
    # A classic multiframe with only one top-level position is not enough to register every frame.
    if int(getattr(ds, "NumberOfFrames", 1)) > 1 and not per_frame:
        return None
    if origin is None or orientation is None or spacing is None:
        return None
    if origin.size != 3 or orientation.size != 6 or spacing.size != 2:
        return None
    if not np.all(np.isfinite(np.r_[origin, orientation, spacing])) or np.any(spacing <= 0):
        return None
    if not np.isclose(np.linalg.norm(orientation[:3]), 1, atol=0.01) or not np.isclose(np.linalg.norm(orientation[3:]), 1, atol=0.01):
        return None
    if not np.isclose(np.dot(orientation[:3], orientation[3:]), 0, atol=0.01):
        return None
    return origin, orientation, spacing


def sample_mask(mask, geometry, rows: int, columns: int) -> np.ndarray:
    """Nearest-neighbour registration: DICOM LPS -> NIfTI RAS -> voxel index."""
    origin, orientation, spacing = geometry
    rr, cc = np.mgrid[:rows, :columns]
    lps = (origin[:, None, None]
           + orientation[:3, None, None] * cc * spacing[1]
           + orientation[3:, None, None] * rr * spacing[0])
    ras = lps * np.array([-1, -1, 1])[:, None, None]
    inv = np.linalg.inv(mask.affine)
    voxels = np.rint(np.einsum("ij,jhw->ihw", inv[:3, :3], ras) + inv[:3, 3, None, None]).astype(int)
    shape = mask.shape[:3]
    valid = np.ones((rows, columns), dtype=bool)
    for axis in range(3):
        valid &= (voxels[axis] >= 0) & (voxels[axis] < shape[axis])
    sampled = np.zeros((rows, columns), dtype=bool)
    data = np.asanyarray(mask.dataobj)
    if data.ndim != 3:
        raise ValueError("Mask is not a 3D volume")
    sampled[valid] = data[tuple(v[valid] for v in voxels)] > 0
    return sampled


def render_pixels(ds, pixels: np.ndarray, index: int) -> Image.Image:
    if pixels.ndim == 3 and pixels.shape[-1] in (3, 4):
        return Image.fromarray(np.asarray(pixels[..., :3], dtype=np.uint8), "RGB")
    if pixels.ndim != 2:
        raise RuntimeError("Ta klatka nie jest obsługiwanym obrazem dwuwymiarowym.")
    values = pixels.astype(np.float32)
    transform = ds
    groups = list(getattr(ds, "PerFrameFunctionalGroupsSequence", []))
    if index < len(groups):
        transforms = getattr(groups[index], "PixelValueTransformationSequence", [])
        if transforms:
            transform = transforms[0]
    values = values * float(getattr(transform, "RescaleSlope", 1)) + float(getattr(transform, "RescaleIntercept", 0))
    center, width = getattr(ds, "WindowCenter", None), getattr(ds, "WindowWidth", None)
    if center is not None and width is not None:
        center = float(center[0] if hasattr(center, "__len__") else center)
        width = float(width[0] if hasattr(width, "__len__") else width)
        low, high = center - width / 2, center + width / 2
    else:
        finite = values[np.isfinite(values)]
        low, high = np.percentile(finite, [1, 99.5]) if finite.size else (0, 1)
    normalized = np.nan_to_num(np.clip((values - low) / max(high - low, 1e-6), 0, 1))
    if str(getattr(ds, "PhotometricInterpretation", "")) == "MONOCHROME1":
        normalized = 1 - normalized
    return Image.fromarray((normalized * 255).astype(np.uint8), "L").convert("RGB")


def alignment_key(ds, geometry):
    """Only overlay identical patient-space grids from the same acquisition family.

    This is a display grouping, not motion correction or image registration.
    Missing geometry never authorizes fusion based on frame/instance numbers.
    """
    reference = str(getattr(ds, "FrameOfReferenceUID", ""))
    if geometry is None or not reference:
        return None
    description = re.sub(r"\s*—\s*wolumen\s+\d+/\d+$", "", str(getattr(ds, "SeriesDescription", "")))
    identity = [reference, int(ds.Rows), int(ds.Columns), description,
                str(getattr(ds, "Modality", "")), str(getattr(ds, "SequenceName", "")),
                str(getattr(ds, "EchoTime", "")), str(getattr(ds, "RepetitionTime", "")),
                str(getattr(ds, "ImageType", "")), str(getattr(ds, "SliceThickness", "")),
                *[np.round(value, 6).tolist() for value in geometry]]
    return hashlib.sha256(json.dumps(identity, allow_nan=False).encode()).hexdigest()


class PreviewService:
    MAX_FRAMES = 4000
    MAX_SOURCE_PIXELS = 600_000_000
    MAX_SHEET_PIXELS = 24_000_000
    MAX_CACHE_BYTES = 8_000_000_000
    MAX_MASK_PIXELS = 150_000_000

    def __init__(self, settings: Settings, source_factory=OrthancSource):
        self._settings = settings
        self._source_factory = source_factory
        self.root = settings.data_dir / "previews-v2"
        self.root.mkdir(parents=True, exist_ok=True)
        # Bound simultaneous decoding and avoid duplicate builds of the same study.
        self._lock = threading.Lock()

    def prepare(self, request: PreviewRequest) -> dict:
        key = hashlib.sha256(request.model_dump_json().encode()).hexdigest()
        output = self.root / key
        manifest = output / "manifest.json"
        with self._lock:
            if manifest.is_file():
                manifest.touch()
                return json.loads(manifest.read_text(encoding="utf-8"))
            self._prune_cache()
            output.mkdir(parents=True, exist_ok=True)
            try:
                with self._source_factory(self._settings) as source:
                    directory = source.download_series(request.studyInstanceUid, request.seriesInstanceUid, output / "source")
                result = self._render(directory, output, request)
                result["cacheKey"] = key
                manifest.write_text(json.dumps(result), encoding="utf-8")
                return result
            except Exception:
                shutil.rmtree(output, ignore_errors=True)
                raise
            finally:
                shutil.rmtree(output / "source", ignore_errors=True)
                shutil.rmtree(output / "masks", ignore_errors=True)

    def asset(self, key: str, filename: str) -> Path:
        if not re.fullmatch(r"[a-f0-9]{64}", key) or not re.fullmatch(r"(?:image-\d+|overlay-\d+|sheet|sheet-overlay|preview)\.png", filename):
            raise FileNotFoundError
        path = self.root / key / filename
        if not path.is_file():
            raise FileNotFoundError
        return path

    def _prune_cache(self):
        directories = [path for path in self.root.iterdir() if path.is_dir()]
        entries = [(path, sum(p.stat().st_size for p in path.rglob("*") if p.is_file())) for path in directories]
        total = sum(size for _, size in entries)
        for path, size in sorted(entries, key=lambda pair: pair[0].stat().st_mtime):
            if total <= self.MAX_CACHE_BYTES:
                break
            shutil.rmtree(path)
            total -= size

    def _frames(self, directory, request):
        frames = []
        total_pixels = 0
        for path in sorted(directory.rglob("*")):
            if not path.is_file():
                continue
            try:
                ds = pydicom.dcmread(path, stop_before_pixels=True)
            except pydicom.errors.InvalidDicomError:
                continue
            if str(getattr(ds, "SeriesInstanceUID", "")) != request.seriesInstanceUid or str(getattr(ds, "StudyInstanceUID", "")) != request.studyInstanceUid:
                continue
            if not hasattr(ds, "Rows") or not hasattr(ds, "Columns") or not hasattr(ds, "SOPInstanceUID"):
                continue
            for frame_index in range(int(getattr(ds, "NumberOfFrames", 1))):
                total_pixels += int(ds.Rows) * int(ds.Columns)
                if len(frames) >= self.MAX_FRAMES or total_pixels > self.MAX_SOURCE_PIXELS or max(int(ds.Rows), int(ds.Columns)) > 8192:
                    raise RuntimeError("Seria jest zbyt duża dla zbiorczego podglądu. Otwórz przeglądarkę zaawansowaną.")
                geometry = frame_geometry(ds, frame_index)
                position = float(np.dot(geometry[0], np.cross(geometry[1][:3], geometry[1][3:]))) if geometry else 0
                frames.append((path, ds, frame_index, geometry, position))
        if not frames:
            raise RuntimeError("W tej serii nie znaleziono obrazów możliwych do wyświetlenia.")
        # Position gives spatial order; instance/frame number resolves repeated locations (e.g. cine).
        frames.sort(key=lambda frame: (round(frame[4], 5), int(getattr(frame[1], "InstanceNumber", 0)), str(frame[1].SOPInstanceUID), frame[2]))
        return frames

    def _masks(self, request, output):
        if not request.analysisId or not request.masks:
            return [], []
        try:
            UUID(request.analysisId)
            for artifact in request.masks:
                UUID(artifact.id)
        except ValueError:
            raise RuntimeError("Invalid analysis artifact identifier")
        result, warnings = [], []
        loaded_voxels = 0
        for artifact in request.masks:
            candidates = list((self._settings.data_dir / request.analysisId / "output").rglob(f"{artifact.id}.zip"))
            if len(candidates) != 1:
                warnings.append("Plik maski z poprzedniej analizy nie jest dostępny w pamięci workera.")
                continue
            target = output / "masks" / artifact.id
            target.mkdir(parents=True)
            with zipfile.ZipFile(candidates[0]) as archive:
                masks = [member for member in archive.infolist() if member.filename.endswith((".nii", ".nii.gz"))]
                if len(masks) > 256 or sum(m.file_size for m in masks) > self._settings.max_archive_bytes:
                    warnings.append("Maski przekraczają limit bezpiecznego podglądu.")
                    continue
                for index, member in enumerate(masks):
                    # Flatten explicitly, never trust member paths from the artifact archive.
                    file = target / f"{index}.nii.gz" if member.filename.endswith(".gz") else target / f"{index}.nii"
                    with archive.open(member) as source, file.open("wb") as destination:
                        shutil.copyfileobj(source, destination)
                    image = nib.load(file)
                    loaded_voxels += math.prod(image.shape)
                    if len(image.shape) != 3 or math.prod(image.shape) > self.MAX_MASK_PIXELS or loaded_voxels > 250_000_000 or not np.all(np.isfinite(image.affine)) or abs(np.linalg.det(image.affine[:3, :3])) < 1e-8 or not (int(image.header["sform_code"]) or int(image.header["qform_code"])):
                        warnings.append("Pominięto maskę bez prawidłowej geometrii przestrzennej.")
                        continue
                    data = np.asanyarray(image.dataobj) > 0
                    if np.any(data):
                        result.append((nib.Nifti1Image(data.astype(np.uint8), image.affine), artifact.kind))
        return result, warnings

    def _render(self, directory: Path, output: Path, request: PreviewRequest) -> dict:
        frames = self._frames(directory, request)
        try:
            masks, warnings = self._masks(request, output)
        except (ValueError, OSError, zipfile.BadZipFile, nib.filebasedimages.ImageFileError):
            masks, warnings = [], ["Nie można odczytać maski z poprzedniej analizy. Obrazy źródłowe pozostają dostępne."]
        count = len(frames)
        cell_width = min(256, max(64, int(math.sqrt(self.MAX_SHEET_PIXELS / count)) - 22))
        cell_height = cell_width + 22
        columns = min(8, max(1, math.ceil(math.sqrt(count))))
        rows = math.ceil(count / columns)
        sheet = Image.new("RGB", (columns * cell_width, rows * cell_height), (7, 19, 24))
        overlay_sheet = Image.new("RGBA", sheet.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(sheet)
        manifest, aligned, visible = [], 0, False
        for index, (path, ds, source_index, geometry, _) in enumerate(frames):
            try:
                pixels = pixel_array(path, index=source_index)
                image = render_pixels(ds, pixels, source_index)
            except Exception as exception:
                raise RuntimeError(f"Nie można odczytać klatki {index + 1}. Użyj przeglądarki zaawansowanej.") from exception
            image.save(output / f"image-{index}.png")
            if index == count // 2:
                preview = image.copy()
                preview.thumbnail((640, 640))
                preview.save(output / "preview.png")
            thumb = image.copy()
            thumb.thumbnail((cell_width, cell_width))
            x = index % columns * cell_width + (cell_width - thumb.width) // 2
            y = index // columns * cell_height + (cell_width - thumb.height) // 2
            sheet.paste(thumb, (x, y))
            draw.text((index % columns * cell_width + 8, index // columns * cell_height + cell_width + 3), f"{index + 1} / {count}", fill=(212, 234, 234))
            has_overlay = False
            if masks and geometry is not None:
                aligned += 1
                rgba = np.zeros((int(ds.Rows), int(ds.Columns), 4), dtype=np.uint8)
                for mask, kind in masks:
                    sampled = sample_mask(mask, geometry, int(ds.Rows), int(ds.Columns))
                    if np.any(sampled):
                        rgba[sampled] = (245, 174, 63, 150) if kind == "CANDIDATE_MASK" else (43, 202, 167, 105)
                        visible = True
                overlay = Image.fromarray(rgba, "RGBA")
                overlay.save(output / f"overlay-{index}.png")
                overlay_sheet.paste(overlay.resize(thumb.size, Image.Resampling.NEAREST), (x, y))
                has_overlay = True
            manifest.append({"id": f"{ds.SOPInstanceUID}:{source_index + 1}", "index": index,
                             "sopInstanceUid": str(ds.SOPInstanceUID), "frameNumber": source_index + 1,
                             "rows": int(ds.Rows), "columns": int(ds.Columns), "hasOverlay": has_overlay,
                             "alignmentKey": alignment_key(ds, geometry)})
        sheet.save(output / "sheet.png")
        if aligned:
            overlay_sheet.save(output / "sheet-overlay.png")
        status = "NOT_ANALYZED" if not request.analysisId else "UNAVAILABLE"
        message = "Nakładka będzie dostępna po zakończeniu analizy tej serii."
        if request.analysisId and not request.masks:
            message = "Analiza nie wygenerowała maski dla tej serii. Obrazy źródłowe są dostępne."
        elif request.masks and not masks:
            message = "Maski tej serii nie zawierają widocznych oznaczeń lub nie są dostępne."
        elif masks and aligned == count and visible:
            status, message = "AVAILABLE", "Kolor pokazuje struktury oznaczone przez model. To oznaczenia pomocnicze, a nie rozpoznanie."
        elif masks:
            status, message = "ALIGNMENT_UNAVAILABLE", "Nie można potwierdzić zgodności przestrzennej maski ze wszystkimi klatkami. Wyświetlono obrazy źródłowe."
            # Do not offer a partially mapped study as if it were complete.
            for frame in manifest:
                frame["hasOverlay"] = False
            aligned = 0
        legend = []
        if status == "AVAILABLE":
            kinds = {kind for _, kind in masks}
            if "ANATOMY_MASKS" in kinds:
                legend.append({"label": "Struktury anatomiczne", "color": "#2bcaa7"})
            if "CANDIDATE_MASK" in kinds:
                legend.append({"label": "Obszar wskazany do oceny", "color": "#f5ae3f"})
        return {"frameCount": count, "frames": manifest, "contactSheetColumns": columns,
                "contactSheetRows": rows, "cellWidth": cell_width, "cellHeight": cell_height,
                "overlayStatus": status, "overlayMessage": message, "legend": legend,
                "hasOverlay": status == "AVAILABLE", "warnings": list(dict.fromkeys(warnings))}
