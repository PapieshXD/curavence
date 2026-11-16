import secrets

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse

from app.config import settings
from app.engine import create_engine
from app.models import AnalysisRequest, AnalysisResponse
from app.service import AnalysisService
from app.summary import EvidenceSummarizer
from app.preview import PreviewRequest, PreviewService
from app.ecg import preview as ecg_preview, MAX_BYTES
from app.ecg_morphology import analyze_file


app = FastAPI(
    title="Ralek Imaging AI Service",
    version="0.1.0",
    description="Non-diagnostic DICOM segmentation and quantitative evidence extraction.",
)
engine = create_engine(settings)
service = AnalysisService(settings, engine, EvidenceSummarizer(settings))
previews = PreviewService(settings)


def authorize(x_service_token: str = Header(default="")) -> None:
    if not settings.service_token or not secrets.compare_digest(x_service_token, settings.service_token):
        raise HTTPException(status_code=401, detail="Invalid service token")


@app.get("/health")
def health() -> dict[str, str]:
    try:
        import wfdb  # noqa: F401 - required to decode WFDB ZIP uploads
        import pyedflib  # noqa: F401 - required to decode EDF/BDF uploads
        import scipy  # noqa: F401 - required for ECG signal processing
    except ImportError as exception:
        raise HTTPException(status_code=503, detail="Brak wymaganej biblioteki dekodera EKG.") from exception
    return {"status": "ok", "engine": engine.name}


@app.post("/v1/ecg/preview", dependencies=[Depends(authorize)])
async def prepare_ecg(request: Request, filename: str, sampling_rate: float | None = None, unit: str | None = None):
    from starlette.concurrency import run_in_threadpool
    content = bytearray()
    async for part in request.stream():
        content.extend(part)
        if len(content) > MAX_BYTES:
            raise HTTPException(status_code=413, detail="Plik EKG przekracza 25 MB.")
    try:
        return await run_in_threadpool(ecg_preview, filename, bytes(content), sampling_rate, unit)
    except ImportError as exception:
        raise HTTPException(status_code=503, detail="Brak dekodera formatu na serwerze. Zaktualizuj serwis EKG.") from exception
    except Exception as exception:
        # No patient content or paths in responses for unexpected decoder errors.
        detail = str(exception) if type(exception) is ValueError else "Nie można odczytać struktury pliku EKG. Sprawdź format eksportu i kompletność zapisu."
        raise HTTPException(status_code=422, detail=detail) from exception


@app.post("/v1/ecg/morphology", dependencies=[Depends(authorize)])
async def ecg_morphology(request: Request, filename: str, sampling_rate: float | None = None,
                         unit: str | None = None, channel_index: int = 0, start_sample: int = 0, sample_count: int = 0):
    from starlette.concurrency import run_in_threadpool
    content = bytearray()
    async for part in request.stream():
        content.extend(part)
        if len(content) > MAX_BYTES:
            raise HTTPException(status_code=413, detail="Plik EKG przekracza 25 MB.")
    try:
        return await run_in_threadpool(analyze_file, filename, bytes(content), sampling_rate, unit, channel_index, start_sample, sample_count)
    except Exception as exception:
        detail = str(exception) if type(exception) is ValueError else 'Nie udało się wykonać analizy P–QRS–T.'
        raise HTTPException(status_code=422, detail=detail) from exception


@app.post("/v1/analyses", response_model=AnalysisResponse, dependencies=[Depends(authorize)])
def analyze(request: AnalysisRequest) -> AnalysisResponse:
    try:
        return service.analyze(request)
    except RuntimeError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception


@app.get("/v1/analyses/{analysis_id}/artifacts/{artifact_id}", dependencies=[Depends(authorize)])
def artifact(analysis_id: str, artifact_id: str) -> FileResponse:
    try:
        path = service.artifact_path(analysis_id, artifact_id)
    except FileNotFoundError as exception:
        raise HTTPException(status_code=404, detail="Artifact not found") from exception
    return FileResponse(path)


@app.post("/v1/previews", dependencies=[Depends(authorize)])
def prepare_preview(request: PreviewRequest) -> dict:
    try:
        return previews.prepare(request)
    except RuntimeError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception


@app.get("/v1/previews/{cache_key}/{filename}", dependencies=[Depends(authorize)])
def preview_asset(cache_key: str, filename: str) -> FileResponse:
    try:
        path = previews.asset(cache_key, filename)
    except FileNotFoundError as exception:
        raise HTTPException(status_code=404, detail="Preview not found") from exception
    return FileResponse(path, media_type="image/png", headers={"Cache-Control": "private, no-store"})
