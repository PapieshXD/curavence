# Segmentacja badań obrazowych

Wdrożony MVP obsługuje dwa jawne pipeline’y: `CT_HEAD_V1` i `MR_BRAIN_V1`.

```text
Angular viewer
    -> Spring Boot: zadanie, autoryzacja, audyt i trwały wynik
        -> FastAPI imaging-ai-service
            -> klasyfikacja DICOM study/series
            -> odrzucenie technicznych localizerów/scoutów
            -> QC każdej zakwalifikowanej serii
            -> TotalSegmentator (total / total_mr) osobno dla każdej serii
            -> cerebral_bleed dla każdej zakwalifikowanej serii CT głowy
            -> pomiary i evidence JSON
        -> MinIO: maski i artefakty
    <- JSON bez pola diagnosis
```

## API aplikacji

- `POST /api/v1/imaging-studies/{studyId}/analyses` — idempotentnie uruchamia analizę.
- `GET /api/v1/imaging-studies/{studyId}/analyses/latest` — ostatni wynik lub `204`.
- `GET /api/v1/imaging-studies/{studyId}/analyses/{analysisId}` — polling zadania.
- `GET /api/v1/imaging-studies/{studyId}/analyses/{analysisId}/artifacts/{artifactId}/content`
  — pobranie maski po ponownej kontroli dostępu do badania.

Wynik rozdziela `measurements`, `observations` i `candidateFindings`. Celowo nie istnieje pole
`diagnosis`. UI używa neutralnych określeń „obszar wymagający uwagi” i pokazuje obowiązkowe
zastrzeżenie o ocenie specjalisty.

Worker nie wybiera już jednej „najlepszej” serii. Pobiera wszystkie diagnostyczne serie obrazowe
zgodne z modalnością badania i wszystkie zawarte w nich instancje. Sekwencje MR (np. FLAIR, DWI,
ADC) są inferowane osobno, a dopiero wyniki są agregowane na poziomie badania; ich piksele nie są
sklejane w jeden sztuczny wolumen. Odpowiedź zawiera listę `selectedSeriesInstanceUids`, łączną
liczbę obrazów, liczbę przeanalizowanych serii oraz QC przypisane do każdej serii.

## Viewer pacjenta

Pacjent domyślnie otwiera badanie w protokole OHIF `mnGrid8`: do ośmiu serii jest rozmieszczanych
automatycznie w siatce (dla typowego badania z siedmioma seriami wszystkie są widoczne od razu).
Panel ręcznego wyboru serii i pole serii przy notatce są ukryte. W menu `Ustawienia` można włączyć
`Pokaż wybór serii`, co odtwarza standardowy viewer OHIF. Widoki personelu pozostają zaawansowane.

## Uruchamianie

`start-all.ps1` uruchamia worker razem z Orthanc. Domyślny silnik to rzeczywisty
TotalSegmentator na CPU w trybie `--fast`. Pierwsza analiza pobiera wagi i może potrwać długo.

Najważniejsze zmienne:

- `SEGMENTATION_ENGINE=totalsegmentator` albo jawny testowy `mock`,
- `SEGMENTATION_DEVICE=cpu|gpu|mps`,
- `SEGMENTATION_FAST=true|false`,
- `SEGMENTATION_RUN_PATHOLOGY_MODELS=true|false`,
- `SEGMENTATION_SERVICE_TOKEN` — wspólny sekret Spring/worker,
- `SEGMENTATION_LLM_ENABLED=false|true` — opcjonalne podsumowanie wyłącznie evidence JSON.

Tryb `mock` nie wykonuje inferencji i oznacza wynik jako `mock-no-medical-inference`.

## Granice MVP

- Gdy metadane nie identyfikują bezpiecznie CT głowy lub MRI mózgu, wynik ma status
  `UNSUPPORTED`; nie wdrożono jeszcze fallbacku ViT/VLM.
- Serie techniczne oznaczone jako localizer/scout/topogram/survey/locator są pomijane, ponieważ nie
  stanowią pełnego wolumenu diagnostycznego i nie powinny trafiać do modelu 3D.
- Maski są przechowywane jako NIfTI w ZIP i dostępne z panelu viewera. Bezpośrednia nakładka w
  OHIF wymaga kolejnego kroku: eksportu DICOM SEG i rejestracji serii pochodnej w Orthanc.
- QC i progi asymetrii są heurystykami technicznymi, a nie zwalidowanymi kryteriami klinicznymi.
- TotalSegmentator nie jest tu używany jako samodzielny wyrób medyczny. Szczegóły narzędzia i
  zadania: https://github.com/wasserth/TotalSegmentator
