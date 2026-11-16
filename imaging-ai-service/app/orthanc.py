import shutil
import zipfile
from pathlib import Path

import httpx

from app.config import Settings


class OrthancSource:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._client = httpx.Client(
            base_url=settings.orthanc_base_url.rstrip("/"),
            auth=(settings.orthanc_username, settings.orthanc_password),
            timeout=httpx.Timeout(120, connect=10),
        )

    def download_series(self, study_uid: str, series_uid: str, target: Path) -> Path:
        target.mkdir(parents=True, exist_ok=True)
        matches = self._client.post(
            "/tools/find",
            json={
                "Level": "Series",
                "Query": {"StudyInstanceUID": study_uid, "SeriesInstanceUID": series_uid},
                "Expand": False,
            },
        )
        matches.raise_for_status()
        identifiers = matches.json()
        if not identifiers:
            raise RuntimeError("Orthanc does not contain the selected DICOM series")
        archive = target / "series.zip"
        with self._client.stream("GET", f"/series/{identifiers[0]}/archive") as response:
            response.raise_for_status()
            total = 0
            with archive.open("wb") as output:
                for chunk in response.iter_bytes():
                    total += len(chunk)
                    if total > self._settings.max_archive_bytes:
                        raise RuntimeError("DICOM archive exceeds the configured size limit")
                    output.write(chunk)
        dicom_dir = target / "dicom"
        dicom_dir.mkdir(parents=True, exist_ok=True)
        self._safe_extract(archive, dicom_dir)
        archive.unlink(missing_ok=True)
        return dicom_dir

    @staticmethod
    def _safe_extract(archive: Path, target: Path) -> None:
        root = target.resolve()
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                destination = (target / member.filename).resolve()
                if root != destination and root not in destination.parents:
                    raise RuntimeError("Unsafe path in Orthanc archive")
            source.extractall(target)

    def close(self) -> None:
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


def flatten_dicom_files(source: Path, target: Path) -> Path:
    """Give TotalSegmentator one directory containing only the selected series."""
    target.mkdir(parents=True, exist_ok=True)
    copied = 0
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        shutil.copy2(path, target / f"{copied:06d}.dcm")
        copied += 1
    if copied == 0:
        raise RuntimeError("The selected Orthanc series archive is empty")
    return target
