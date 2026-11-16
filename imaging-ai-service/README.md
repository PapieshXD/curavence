# Ralek imaging AI service

Worker wykonuje bezdiagnostyczny pipeline dla `CT_HEAD_V1` i `MR_BRAIN_V1`:

1. klasyfikacja badania i serii na podstawie metadanych DICOM,
2. pobranie wybranej serii z Orthanc,
3. heurystyczna kontrola jakości na równomiernie wybranych przekrojach,
4. segmentacja anatomii przez TotalSegmentator (`total` albo `total_mr`),
5. pomiary objętości i intensywności oraz asymetria struktur sparowanych,
6. opcjonalny model `cerebral_bleed` dla CT głowy,
7. JSON evidence i maski NIfTI w archiwum ZIP.

Opcjonalny lokalny LLM dostaje wyłącznie evidence JSON. Ustaw
`SEGMENTATION_LLM_ENABLED=true`, `SEGMENTATION_LLM_BASE_URL` i `SEGMENTATION_LLM_MODEL`, aby
włączyć podsumowanie; przy braku modelu zawsze używany jest deterministyczny fallback.

Worker nie zwraca pola `diagnosis`. Obserwacje i candidate findings zawsze mają status
`requires_review`.

## Uruchomienie

Najprościej uruchomić cały stos z katalogu głównego przez Docker Compose. Pierwsza rzeczywista
analiza pobiera wagi TotalSegmentator i może trwać długo na CPU. Do testów kontraktu bez inferencji:

```powershell
$env:SEGMENTATION_ENGINE='mock'
docker compose up --build imaging-ai-service
```

Tryb `mock` jest jawnie oznaczony jako `mock-no-medical-inference` i nie generuje masek.
