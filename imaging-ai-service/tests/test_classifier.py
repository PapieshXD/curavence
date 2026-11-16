from app.classifier import classify_sequence, classify_study
from app.models import AnalysisRequest, SeriesInput, StudyInput


def test_routes_ct_head_and_avoids_localizer():
    result = classify_study(StudyInput(
        study_instance_uid="1.2.3",
        storage_provider="ORTHANC",
        modality="CT",
        body_part_examined="HEAD",
        series=[
            SeriesInput(series_instance_uid="scout", description="SCOUT", image_count=2),
            SeriesInput(series_instance_uid="axial", description="AX BRAIN SOFT", image_count=180),
        ],
    ))

    assert result.pipeline == "CT_HEAD_V1"
    assert result.selected_series_instance_uid == "axial"
    assert result.selected_series_instance_uids == ["axial"]
    assert [series.series_instance_uid for series in result.selected_series] == ["axial"]
    assert result.source == "DICOM_METADATA"


def test_routes_mr_brain_and_classifies_flair():
    result = classify_study(StudyInput(
        study_instance_uid="1.2.4",
        storage_provider="ORTHANC",
        modality="MR",
        study_description="MRI BRAIN",
        series=[
            SeriesInput(series_instance_uid="dwi", description="AX DWI", image_count=30),
            SeriesInput(series_instance_uid="flair", description="AX T2 FLAIR", image_count=160),
        ],
    ))

    assert result.pipeline == "MR_BRAIN_V1"
    assert result.selected_series_instance_uid == "flair"
    assert result.selected_series_instance_uids == ["flair", "dwi"]
    assert classify_sequence("AX T2 FLAIR", None) == "FLAIR"


def test_does_not_guess_anatomy_when_metadata_is_empty():
    result = classify_study(StudyInput(
        study_instance_uid="1.2.5",
        storage_provider="ORTHANC",
        modality="MR",
        series=[SeriesInput(series_instance_uid="series", description="SERIES_501", image_count=100)],
    ))

    assert result.pipeline == "UNSUPPORTED"
    assert result.source == "UNKNOWN"


def test_worker_contract_accepts_and_emits_camel_case():
    request = AnalysisRequest.model_validate({
        "analysisId": "11111111-2222-3333-4444-555555555555",
        "study": {
            "studyInstanceUid": "1.2.3",
            "storageProvider": "ORTHANC",
            "modality": "CT",
            "bodyPartExamined": "HEAD",
            "series": [{
                "seriesInstanceUid": "1.2.3.4",
                "modality": "CT",
                "description": "AX BRAIN",
                "imageCount": 100,
            }],
        },
        "options": {"runPathologyModels": True},
    })

    dumped = request.model_dump(mode="json", by_alias=True)
    assert request.study.series[0].image_count == 100
    assert dumped["analysisId"] == "11111111-2222-3333-4444-555555555555"
    assert dumped["study"]["series"][0]["imageCount"] == 100
