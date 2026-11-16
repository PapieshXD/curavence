# Curavence

Aplikacja do porządkowania dokumentacji medycznej pacjenta, przeglądania wyników i obrazów oraz udostępniania danych lekarzom w określonym zakresie.

## Struktura repozytorium

| Katalog | Zawartość |
|---|---|
| `backend/` | Java 21, Spring Boot, REST API, migracje PostgreSQL |
| `frontend/` | Angular 21, interfejs pacjenta i lekarza |
| `imaging-ai-service/` | Python, przetwarzanie obrazów medycznych i EKG |
| `infrastructure/` | Konfiguracje developerskie Keycloak, Orthanc i OHIF |
| `postman/` | Kolekcja API dla środowiska lokalnego |
| `docs/` | Dokumentacja funkcjonalna, techniczna i CI |
| `.github/` | GitHub Actions, kontrola jakości i Dependabot |

## Uruchomienie lokalne

Wymagania: Docker Desktop z silnikiem Linux, Java 21, Node.js 22.12+ i npm 11.6.2. Pierwsze budowanie usługi obrazowej pobiera duże zależności segmentacji.

W PowerShell z głównego katalogu:

```powershell
.\start-all.ps1
```

Skrypt uruchamia lokalną infrastrukturę przez Docker Compose, backend i frontend. Konfiguracje Compose, importowane konta i hasła domyślne służą wyłącznie do pracy developerskiej. Nie używaj ich jako konfiguracji produkcyjnej ani do przechowywania rzeczywistych danych pacjentów.

Informacje o narzędziach, testach, raportach, wymaganym checku i wydawaniu obrazów do GHCR znajdują się w [dokumentacji GitHub Actions](docs/github-actions.md).

## Dokumentacja

- [API i Postman](postman/README.md)
- [Usługa obrazowa](imaging-ai-service/README.md)
- [Przygotowanie i ciągłość wizyty](docs/visit-continuity.md)
- [Objaśnianie dokumentów](docs/reading-guide.md)
- [Natywna przeglądarka DICOM](NATIVE_DICOM_VIEWER.md)
- [Przeglądanie EKG](ECG_PREVIEW.md)
- [Segmentacja](SEGMENTATION.md)

Kod, testy i konfiguracje są przechowywane razem. Lokalne dane, środowiska Python, zależności, raporty oraz prywatny plik danych logowania są wykluczone przez `.gitignore`. Funkcje AI i zakresy referencyjne nie zastępują oceny medycznej.
