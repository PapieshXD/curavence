import json
import zipfile
from pathlib import Path

import nibabel as nib
import numpy as np
import pydicom
import pytest
from PIL import Image
from pydicom.dataset import FileDataset, FileMetaDataset, Dataset
from pydicom.uid import ExplicitVRLittleEndian, MRImageStorage

from app.config import Settings
from app.preview import PreviewRequest, PreviewService, frame_geometry, sample_mask


STUDY = "1.2.840.1"
SERIES = "1.2.840.2"
ANALYSIS = "11111111-2222-3333-4444-555555555555"
ARTIFACT = "21111111-2222-3333-4444-555555555555"


def dicom(path: Path, z: int, frames: int = 1):
    meta = FileMetaDataset()
    meta.TransferSyntaxUID = ExplicitVRLittleEndian
    meta.MediaStorageSOPClassUID = MRImageStorage
    meta.MediaStorageSOPInstanceUID = f"1.2.840.3.{z + 1}"
    ds = FileDataset(str(path), {}, file_meta=meta, preamble=b"\0" * 128)
    ds.StudyInstanceUID, ds.SeriesInstanceUID = STUDY, SERIES
    ds.SOPInstanceUID = meta.MediaStorageSOPInstanceUID
    ds.SOPClassUID = MRImageStorage
    ds.Rows, ds.Columns = 12, 16
    ds.SamplesPerPixel, ds.PhotometricInterpretation = 1, "MONOCHROME2"
    ds.BitsAllocated = ds.BitsStored = 16
    ds.HighBit, ds.PixelRepresentation = 15, 0
    ds.InstanceNumber = z + 1
    ds.ImagePositionPatient = [0, 0, z]
    ds.ImageOrientationPatient = [1, 0, 0, 0, 1, 0]
    ds.PixelSpacing = [1, 1]
    pixels = np.arange(192, dtype=np.uint16).reshape(12, 16)
    if frames > 1:
        ds.NumberOfFrames = frames
        pixels = np.stack([pixels + i for i in range(frames)])
        ds.PerFrameFunctionalGroupsSequence = []
        for index in range(frames):
            group, position = Dataset(), Dataset()
            position.ImagePositionPatient = [0, 0, index]
            group.PlanePositionSequence = [position]
            ds.PerFrameFunctionalGroupsSequence.append(group)
    ds.PixelData = pixels.tobytes()
    ds.save_as(path, enforce_file_format=True)
    return ds


class Source:
    def __init__(self, _settings): pass
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def download_series(self, study_uid, series_uid, target):
        assert (study_uid, series_uid) == (STUDY, SERIES)
        target.mkdir(parents=True)
        # Deliberately reverse filename order: the rendered order must be spatial.
        dicom(target / "a.dcm", 2)
        dicom(target / "c.dcm", 0)
        dicom(target / "b.dcm", 1)
        return target


def test_all_frames_middle_preview_and_exact_lps_ras_overlay(tmp_path):
    masks = tmp_path / ANALYSIS / "output"
    masks.mkdir(parents=True)
    data = np.zeros((16, 12, 3), dtype=np.uint8)
    data[4:7, 2:5, 1] = 1
    mask = nib.Nifti1Image(data, np.diag([-1, -1, 1, 1]))
    file = tmp_path / "mask.nii.gz"
    nib.save(mask, file)
    with zipfile.ZipFile(masks / f"{ARTIFACT}.zip", "w") as archive:
        archive.write(file, "brain.nii.gz")
    service = PreviewService(Settings(data_dir=tmp_path), Source)
    request = PreviewRequest(studyInstanceUid=STUDY, seriesInstanceUid=SERIES,
                             analysisId=ANALYSIS, masks=[{"id": ARTIFACT, "kind": "ANATOMY_MASKS"}])
    result = service.prepare(request)
    assert result["frameCount"] == 3
    assert [frame["sopInstanceUid"] for frame in result["frames"]] == ["1.2.840.3.1", "1.2.840.3.2", "1.2.840.3.3"]
    assert result["overlayStatus"] == "AVAILABLE"
    key = result["cacheKey"]
    overlay = np.asarray(Image.open(service.asset(key, "overlay-1.png")))
    assert np.count_nonzero(overlay[..., 3]) == 9
    assert overlay[2, 4, 3] > 0
    assert overlay[4, 2, 3] == 0  # row/column must not be transposed
    assert not np.asarray(Image.open(service.asset(key, "overlay-0.png")))[..., 3].any()
    sheet = Image.open(service.asset(key, "sheet.png"))
    assert sheet.size == (result["cellWidth"] * result["contactSheetColumns"], result["cellHeight"] * result["contactSheetRows"])
    assert Image.open(service.asset(key, "sheet-overlay.png")).size == sheet.size
    assert np.array_equal(np.asarray(Image.open(service.asset(key, "preview.png"))), np.asarray(Image.open(service.asset(key, "image-1.png"))))
    assert service.prepare(request) == result


def test_preview_without_analysis_does_not_invent_a_mask(tmp_path):
    result = PreviewService(Settings(data_dir=tmp_path), Source).prepare(PreviewRequest(studyInstanceUid=STUDY, seriesInstanceUid=SERIES))
    assert result["overlayStatus"] == "NOT_ANALYZED"
    assert not result["hasOverlay"]
    assert all(not frame["hasOverlay"] for frame in result["frames"])


def test_enhanced_multiframe_preserves_source_frame_number(tmp_path):
    directory = tmp_path / "input"
    directory.mkdir()
    ds = dicom(directory / "enhanced.dcm", 0, frames=3)
    service = PreviewService(Settings(data_dir=tmp_path))
    frames = service._frames(directory, PreviewRequest(studyInstanceUid=STUDY, seriesInstanceUid=SERIES))
    assert len(frames) == 3
    assert [frame[2] for frame in frames] == [0, 1, 2]
    assert [frame[3][0][2] for frame in frames] == [0, 1, 2]
    del ds.PerFrameFunctionalGroupsSequence
    assert frame_geometry(ds, 1) is None


def test_mask_registration_handles_patient_origin_and_anisotropic_spacing():
    data = np.zeros((10, 12, 4), dtype=np.uint8)
    data[3, 2, 1] = 1
    affine = np.array([[-2, 0, 0, -10], [0, -3, 0, -20], [0, 0, 4, 30], [0, 0, 0, 1]], dtype=float)
    mask = nib.Nifti1Image(data, affine)
    geometry = (np.array([10, 20, 34]), np.array([1, 0, 0, 0, 1, 0]), np.array([3, 2]))
    sampled = sample_mask(mask, geometry, 12, 10)
    assert sampled[2, 3]
    assert np.count_nonzero(sampled) == 1


def test_preview_limits_and_path_access(tmp_path):
    service = PreviewService(Settings(data_dir=tmp_path), Source)
    service.MAX_FRAMES = 2
    with pytest.raises(RuntimeError, match="zbyt duża"):
        service.prepare(PreviewRequest(studyInstanceUid=STUDY, seriesInstanceUid=SERIES))
    assert not list(service.root.iterdir())
    with pytest.raises(FileNotFoundError):
        service.asset("..", "../../mask.nii.gz")
