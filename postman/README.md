# API Curavence do Postmana

Spis powstał z aktualnych kontrolerów Spring Boot i tras FastAPI w tym katalogu (31.10.2026). Zawiera **62 trasy backendu** i **7 tras lokalnego serwisu analizy**. Kolekcja: [Curavence.local.postman_collection.json](Curavence.local.postman_collection.json). To inwentarz kodu; nie jest potwierdzeniem, że wszystkie usługi są obecnie uruchomione.

## Pierwsze uruchomienie

1. Uruchom aplikację skryptem `start-all.cmd` z katalogu głównego.
2. Zaimportuj plik JSON do Postmana. Ustaw `baseUrl=http://127.0.0.1:8080` i ewentualnie `workerUrl=http://127.0.0.1:8090`.
3. W zmiennych kolekcji wpisz `username` i `password` lokalnego konta z pliku `DANE_LOGOWANIA_TESTOWE.txt`. Nie zapisuj haseł ani tokenów w repozytorium.
4. Wywołaj `GET /api/health`, potem `POST /api/auth/login`. Test Postmana zapisuje `accessToken` jako zmienną `token`. Wszystkie pozostałe zwykłe żądania używają `Authorization: Bearer {{token}}`.
5. Ustaw `workspaceRole` na `patient`, `doctor`, `therapy` lub `admin` zgodnie z zalogowanym kontem. Nagłówek `X-Workspace-Role` jest wymagany przez trasy wizyt i wpływa na widok pozostałych tras.
6. Wywołaj `GET /api/workspace`. Skopiuj potrzebne identyfikatory do zmiennych kolekcji: `patientId`, `studyId`, `assetId` itd. Wartości `{{...}}` w adresach są zmiennymi Postmana.

Daty typu `Instant` zapisuj w ISO 8601, np. `2027-01-31T12:00:00Z`; `LocalDate` jako `2026-11-15`. Formularze plikowe wymagają wskazania lokalnego pliku w Postmanie. Dane pacjenta i badania muszą odpowiadać rzeczywistym ID i uprawnieniom. Odpowiedź `401` zwykle oznacza brak lub wygaśnięcie tokenu, a `403` brak uprawnień albo dostępu do danego badania.

## Pełna lista

### Start i logowanie

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| GET | `/api/health` | Stan backendu |
| POST | `/api/auth/login` | Login; zapisuje accessToken jako token |
| GET | `/api/workspace` | Dane widoku i identyfikatory pacjenta, badań, zasobów |

### Badania i dokumenty

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| POST | `/api/studies` | Dodanie ogólnego badania: multipart file, title, studyTypeCode, patientId, performedAt |
| GET | `/api/assets/{{assetId}}/content?download=false` | Oryginalny plik; download=true wymusza załącznik |
| POST | `/api/documents` | Dodanie dokumentu: multipart file, title, patientId, performedAt, typeCode |
| PUT | `/api/documents/{{documentId}}` | Zmiana metadanych dokumentu |
| DELETE | `/api/documents/{{documentId}}` | Archiwizacja dokumentu |
| GET | `/api/documents/{{documentId}}/clinical-results` | Wydobyte wyniki kliniczne |
| POST | `/api/documents/{{documentId}}/questions` | Pytanie o dokument |
| GET | `/api/documents/{{documentId}}/ecg?samplingRate=500&unit=mV` | Podgląd EKG; parametry opcjonalne |
| POST | `/api/documents/{{documentId}}/ecg/calibration-assessment` | Ocena kalibracji EKG |
| GET | `/api/documents/{{documentId}}/ecg/morphology?channelIndex=0&startSample=0&sampleCount=0` | Morfologia EKG; opcjonalnie samplingRate i unit |
| POST | `/api/documents/{{documentId}}/ecg/questions?audience=patient&channelIndex=0&startSample=0&sampleCount=0` | Pytanie o EKG; opcjonalnie samplingRate i unit |

### Udostępnianie

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| POST | `/api/access-grants` | Bezpośrednia zgoda pacjenta |
| DELETE | `/api/access-grants/{{grantId}}` | Cofnięcie zgody |
| POST | `/api/sharing/codes` | Pacjent tworzy kod jednorazowy |
| DELETE | `/api/sharing/codes/{{codeId}}` | Pacjent unieważnia kod |
| POST | `/api/sharing/requests` | Specjalista używa kodu |
| GET | `/api/sharing/requests` | Lista próśb o dostęp |
| POST | `/api/sharing/requests/{{requestId}}/decision` | Pacjent zatwierdza lub odrzuca; studyIds przy zatwierdzeniu |

### Wizyty i opinie

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| GET | `/api/visits` | Lista pakietów wizyt |
| POST | `/api/visits` | Pacjent tworzy pakiet |
| PUT | `/api/visits/{{visitId}}` | Pacjent edytuje szkic |
| POST | `/api/visits/{{visitId}}/share` | Pacjent udostępnia lekarzowi |
| PUT | `/api/visits/{{visitId}}/plan` | Lekarz zapisuje lub zatwierdza plan |
| PUT | `/api/visits/{{visitId}}/actions/{{actionId}}` | Pacjent oznacza działanie |
| POST | `/api/studies/{{studyId}}/reviews` | Lekarz dodaje opinię |
| POST | `/api/studies/{{studyId}}/reviews/rewrite` | Propozycja uproszczenia opinii |
| PUT | `/api/patients/me` | Pacjent aktualizuje numer telefonu |

### Obrazowanie

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| POST | `/api/v1/patients/{{patientId}}/imaging-studies` | DICOM ZIP lub pliki: multipart files, description, performedAt |
| GET | `/api/v1/patients/{{patientId}}/imaging-studies` | Lista badań obrazowych pacjenta |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}` | Szczegóły i serie |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/status` | Stan przetwarzania |
| POST | `/api/v1/imaging-studies/{{imagingStudyId}}/retry` | Ponowienie importu |
| POST | `/api/v1/imaging-studies/{{imagingStudyId}}/viewer-session?advanced=false` | Sesja OHIF; zwraca adres przeglądarki |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/overview` | Przegląd; opcjonalnie seriesId |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/preview` | Miniatura PNG |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/series/{{seriesId}}/contact-sheet?overlay=false` | Arkusz PNG |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/series/{{seriesId}}/frames/{{frameIndex}}/image` | Klatka źródłowa PNG |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/series/{{seriesId}}/frames/{{frameIndex}}/overlay` | Warstwa nakładki PNG |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/annotations` | Lista adnotacji |
| POST | `/api/v1/imaging-studies/{{imagingStudyId}}/annotations` | Utwórz adnotację |
| PUT | `/api/v1/imaging-studies/{{imagingStudyId}}/annotations/{{annotationId}}` | Zmień adnotację; version z odpowiedzi GET |
| DELETE | `/api/v1/imaging-studies/{{imagingStudyId}}/annotations/{{annotationId}}` | Usuń adnotację |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/viewer-capabilities` | Możliwości przeglądarki |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/view-preferences` | Preferencje widoku |
| PUT | `/api/v1/imaging-studies/{{imagingStudyId}}/view-preferences` | Zapis preferencji |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/analysis-history` | Historia analiz |
| PATCH | `/api/v1/imaging-studies/{{imagingStudyId}}/analysis/{{analysisId}}/scope` | Nowa analiza z nowym zakresem |
| POST | `/api/v1/imaging-studies/{{imagingStudyId}}/analysis/{{analysisId}}/feedback` | Opinia lekarza o wskazaniu |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/analysis/{{analysisId}}/feedback` | Opinie lekarza |

### Analiza obrazów

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| POST | `/api/v1/imaging-studies/{{imagingStudyId}}/analyses` | Start analizy; alias /analysis |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/analyses/capabilities` | Obsługiwane analizy; alias /analysis |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/analyses/latest?audience=patient` | Ostatnia analiza; alias /analysis |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/analyses/{{analysisId}}` | Szczegóły analizy; alias /analysis |
| GET | `/api/v1/imaging-studies/{{imagingStudyId}}/analyses/{{analysisId}}/artifacts/{{artifactId}}/content` | Pobierz artefakt; alias /analysis |

### AI i techniczne

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| GET | `/api/ai/capabilities` | Możliwości AI |
| POST | `/api/ai/explanations` | Zlecenie wyjaśnienia; lokalnie AI może działać w mock mode |
| POST | `/api/ai/callbacks/explanations` | Callback workera; techniczny endpoint bez Bearer |
| GET | `/api/v1/viewer/{{sessionId}}/config?simplified=false` | Konfiguracja OHIF; sesja/cookie |
| GET | `/api/v1/viewer/{{sessionId}}/dicomweb/studies` | Proxy DICOMweb; sesja/cookie, różne ścieżki i HEAD |

### Serwis analizy 8090

| Metoda | Endpoint | Co robi / dane wejściowe |
|---|---|---|
| GET | `/health` | Stan serwisu |
| POST | `/v1/ecg/preview?filename=sample.csv&sampling_rate=500&unit=mV` | Surowe bajty pliku w body; techniczny |
| POST | `/v1/ecg/morphology?filename=sample.csv&sampling_rate=500&unit=mV&channel_index=0&start_sample=0&sample_count=0` | Surowe bajty pliku w body; techniczny |
| POST | `/v1/analyses` | Analiza DICOM; techniczny |
| GET | `/v1/analyses/{{analysisId}}/artifacts/{{artifactId}}` | Pobierz artefakt |
| POST | `/v1/previews` | Przygotuj podgląd |
| GET | `/v1/previews/{{cacheKey}}/{{filename}}` | Pobierz PNG |

## Przydatne uwagi

- `POST /api/studies` i `POST /api/documents` to odrębne sposoby dodawania plików. Przy dokumentach medycznych zwykle zacznij od `/api/documents`, a wynik odczytaj przez `/api/documents/{documentId}/clinical-results`.
- Bezpośredni `POST /api/access-grants` przyjmuje zakresy `FULL_STUDY`, `SELECTED_STUDIES`, `LAB_RESULTS`, `DIAGNOSTIC_REPORT`, `REHABILITATION_SUMMARY` i `TRAINING_RESTRICTIONS`. Przy `SELECTED_STUDIES` wskaż dostępne `studyIds`.
- Przy `/api/sharing/codes` najpierw zaloguj pacjenta, a przy `/api/sharing/requests` specjalistę. Decyzję podejmuje pacjent. Dostęp do badań zależy od zakresu zgody. Zakresy kodu: `FULL_STUDY`, `LAB_RESULTS`, `IMAGING_STUDIES`, `SELECTED_STUDIES`.
- Kolejność wizyty: pacjent tworzy pakiet → udostępnia lekarzowi → lekarz zapisuje plan (`approved: false`) lub go zatwierdza (`approved: true`) → pacjent oznacza działania. Zatwierdzony plan jest końcowy dla tej wizyty.
- Analizy obrazów są asynchroniczne. Po `POST /analyses` sprawdzaj `/analyses/latest` lub `/{analysisId}`. Ścieżka `/analysis` jest aliasem `/analyses` dla wszystkich pięciu tras w grupie „Analiza obrazów”. Dopuszczalne `scopeMode`: `ALL_ELIGIBLE`, `SELECTED_SERIES`, `REANALYZE`; `pipeline`: `AUTO`.
- `GET /api/v1/viewer/{sessionId}/config` i proxy DICOMweb korzystają z krótkiej sesji OHIF oraz cookie przeglądarki. Samo podstawienie losowego `sessionId` nie wystarczy. Proxy obsługuje ścieżki DICOMweb i metody dopuszczone przez walidację sesji; w kolekcji jest przykładowe `GET /studies`.
- `POST /api/ai/callbacks/explanations` to techniczny callback. W kodzie nie wymaga Bearer i jedynie przyjmuje zgłoszenie; nie jest zwykłą funkcją pacjenta.
- Serwis na porcie 8090 jest wewnętrznym workerem. Poza `/health` wymaga nagłówka `X-Service-Token`. Jego wartość pochodzi z lokalnej konfiguracji `SEGMENTATION_SERVICE_TOKEN`. Żądania `/v1/ecg/*` wysyłają surowe bajty pliku w body, z nazwą pliku w query. Do codziennych testów używaj chronionych tras backendu `/api/documents/{documentId}/ecg*`.
- `POST /api/ai/explanations` może działać w lokalnym trybie mock zgodnie z konfiguracją. Wynik nie służy do diagnozy.
