# CI, analiza kodu i wydania

## Co jest uruchamiane

- **CI**: każdy PR, push na domyślną gałąź i ręczne uruchomienie. Współdzielony workflow `checks.yml` uruchamia backend, frontend, Python i kontrolę repozytorium równolegle.
- **Weekly security**: poniedziałek 05:23 UTC oraz ręcznie. Pełna historia Gitleaks, pełny Semgrep i aktualna baza podatności Trivy. Uruchamia też testy, żeby sprawdzić te same artefakty co CI.
- **CodeQL**: tylko publiczne repozytoria, Java, TypeScript/JavaScript i Python. PR, domyślna gałąź, poniedziałek 05:43 UTC i ręcznie. Prywatne repo nie wymaga płatnego Code Security.
- **Release images**: tag `vMAJOR.MINOR.PATCH`, np. `v1.0.0`. Commit musi należeć do historii domyślnej gałęzi. Przed publikacją wykonywane jest ponownie całe CI dla tego tagu.

W Settings → Rules → Rulesets (lub Branch protection) dla domyślnej gałęzi wybierz wymagany check **required** z workflow **CI** (w interfejsie może być pokazany jako `CI / required`). Check musi najpierw wystąpić w repozytorium. Dostępność ochrony gałęzi zależy od planu GitHub. Nie dodawaj jako wymaganych zadań pomijanych warunkowo, np. CodeQL w prywatnym repo.

Akcje mają pełne SHA, checkout nie zachowuje tokenu. Kod z PR uruchamia się przez `pull_request`, nigdy `pull_request_target`. PR z forka nie wymaga sekretów. Uprawnienia zapisu do GHCR ma wyłącznie zadanie publikacji. Nie ma automatycznego merge ani deploymentu.

## Kontrole i raporty

| Moduł | Kontrole |
|---|---|
| Backend | Java 21, Maven `verify -Pquality`, PostgreSQL 17, SpotBugs + FindSecBugs, JaCoCo, Trivy zbudowanego JAR |
| Frontend | Node 22.12.0, npm 11.6.2, ESLint + angular-eslint, Vitest/V8 coverage, produkcyjny build i budżety, Trivy lockfile z zależnościami developerskimi |
| Imaging AI | Python 3.11, zamrożone zależności, pip check, importy dekoderów, Ruff, pytest-cov, Trivy faktycznie zainstalowanych pakietów |
| Repozytorium | Gitleaks, lokalne reguły Semgrep CE, actionlint, zizmor, Hadolint, ShellCheck, walidacja wyjątków i testy bramek |

Raporty są w artefaktach danego przebiegu; Job Summary zawiera odnośniki. Retencja zwykłych raportów i builda frontendu: 7 dni. Raporty wydania, CycloneDX SBOM i digesty: 30 dni. Coverage nie ma progu blokującego; nie wykluczamy kodu produkcyjnego dla poprawiania wyniku. Formatowanie nie blokuje PR i nie jest automatycznie stosowane.

CodeQL publikuje wyniki do Security wyłącznie dla repozytoriów publicznych. Pozostałe raporty działają jako artefakty bez płatnego panelu SARIF. Semgrep używa wyłącznie lokalnych reguł, bez konta i z wyłączonymi metrykami. Trivy pobiera bazy podatności, ale ma wyłączoną telemetrię. Analizatory nie wysyłają kodu do zewnętrznych dashboardów.

Sam analizator musi poprawnie zakończyć skan i utworzyć czytelny raport. Brak raportu, błędy parsowania lub brak zinwentaryzowanych pakietów nie oznaczają sukcesu. Ważne: Trivy `fs` celowo wyłącza analizę JAR i zainstalowanych pakietów; dlatego backend i zainstalowany Python korzystają z `rootfs`.

## Baseline i podatności

`.github/quality/baseline.json` zawiera początkowe, rzeczywiście zmierzone ustalenia ESLint, Ruff, SpotBugs i Semgrep. Porównujemy regułę, plik i treść problematycznej linii (SpotBugs: hash instancji). Przesunięcie kodu o linie nie resetuje baseline. Liczba powtórzeń jest uwzględniona, więc skopiowanie problemu w tym samym miejscu nie ukrywa nowego wystąpienia.

**Nie aktualizuj baseline automatycznie w CI.** Zmiany baseline i konfiguracji analizatorów wymagają zwykłego przeglądu kodu. Lokalny zapis po świadomym przeglądzie:

```sh
python .github/scripts/quality.py scan eslint --record
python .github/scripts/quality.py scan ruff --record
python .github/scripts/quality.py scan semgrep --record
python .github/scripts/quality.py gate spotbugs backend/target/spotbugsXml.xml --record
```

Gitleaks nie ma baseline, a raporty i logi mają pełną redakcję sekretów. Zignorowanie lokalnego pliku nie usuwa go z historii: jeśli sekret został już wypchnięty, należy go unieważnić.

Trivy blokuje `HIGH` i `CRITICAL` z dostępną poprawką. PR porównuje je z **tym samym aktualnym DB** dla wersji bazowej: lockfile npm, przebudowany JAR backendu i zamrożone wymagania Pythona. Zmiana wersji podatnego pakietu tworzy nowe ustalenie. Brak referencyjnego lockfile podczas pierwszego wdrożenia skutkuje pełną kontrolą, a nie automatyczną akceptacją. Push na domyślną gałąź, harmonogram i wydanie kontrolują wszystkie ustalenia. Początkowo mogą być czerwone ze względu na istniejące zależności; workflow nie naprawia ich ani nie akceptuje bez decyzji.

Wyjątki bezpieczeństwa zapisuje się w `.github/quality/exceptions.json`:

```json
[
  {
    "id": "CVE-YYYY-NNNN",
    "tool": "trivy",
    "reason": "Uzasadnienie i numer zadania naprawczego",
    "owner": "osoba lub zespół",
    "created": "YYYY-MM-DD",
    "expires": "YYYY-MM-DD"
  }
]
```

Wyjątek musi być ważny dzisiaj, mieć maksymalnie 30 dni i nie może dotyczyć Gitleaks. `id` może być dokładnym identyfikatorem ustalenia wyświetlanym przez bramkę, aby ograniczyć wyjątek do pakietu i wersji. Nie dodajemy wyjątków zbiorczych ani bezterminowych. Daty dotyczą UTC na runnerze.

## Polecenia lokalne

Z głównego katalogu, w aktywnym Python 3.11:

```sh
python -m pip install -r .github/quality/requirements.txt
python .github/scripts/install_tools.py actionlint gitleaks hadolint trivy
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/quality.py exceptions
python .github/scripts/quality.py scan semgrep
```

Instalator sprawdza sumy kontrolne opublikowane przy konkretnej wersji. Binarne narzędzia zapisuje w `.runtime/ci-tools`; dodaj ten katalog do `PATH`. Na GitHub robi to przez `GITHUB_PATH`. Hadolint i zizmor blokują poziom error/high; drobniejsze zalecenia pozostają widoczne w logach.

Frontend:

```sh
cd frontend
npm ci
npm run lint
npm run lint:raw
npm run coverage
npm run build -- --configuration production
```

`lint` uwzględnia baseline; `lint:raw` pokazuje całość. `coverage` ogranicza liczbę workerów do dwóch, aby uniknąć timeoutów uruchamiania procesów na laptopach i małych runnerach.

Backend:

```sh
cd backend
TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/med_node \
TEST_POSTGRES_USER=med_node TEST_POSTGRES_PASSWORD=ci-only-password \
bash ./mvnw -B -ntp -Pquality verify
cd ..
python .github/scripts/check_postgres_tests.py
python .github/scripts/quality.py gate spotbugs backend/target/spotbugsXml.xml
```

W PowerShell użyj `$env:TEST_POSTGRES_URL=...` itd. oraz `./mvnw.cmd`. Baza musi być testowa: testy tworzą i usuwają własne schematy. CI sprawdza, czy każda klasa `*PostgresTest` wykonała testy bez skipów. Sam zielony Maven bez tej kontroli nie wystarcza.

Python, na Linuxie zgodnym z CI:

```sh
python -m pip install -r imaging-ai-service/requirements-ci.lock
python -m pip install --no-deps -e 'imaging-ai-service[test]'
python -m pip check
python .github/scripts/quality.py scan ruff
cd imaging-ai-service
python -m pytest --cov=app --cov-report=html --cov-report=xml
```

Lock jest rozwiązany dla Python 3.11 / Linux x86_64; zawiera np. uvloop. Na Windows do lokalnych testów zainstaluj `imaging-ai-service[test,ci]`, zamiast instalować linuksowy lock. Aktualizacja locka: `uv==0.9.9`, komenda z nagłówka pliku; zachowaj przegląd zmian i uruchom testy. Dependabot aktualizuje manifesty, a lock trzeba odświeżyć w tym samym PR. Pakiety i narzędzia aktualizujemy przez review, bez automatycznego merge.

`python .github/scripts/probe_scanners.py` sprawdza rzeczywiste wykrywanie fikcyjnego sekretu, niebezpiecznego workflow, błędu ESLint i podatnego lockfile. Wymaga zainstalowanych narzędzi w PATH i `npm ci`. Próbki powstają wyłącznie w katalogu tymczasowym i są usuwane.

## Obrazy i publikacja

Przed pierwszym wydaniem włącz Actions oraz publikowanie pakietów w ustawieniach organizacji/repozytorium. Token jest dostarczany automatycznie jako `GITHUB_TOKEN`; PAT nie jest potrzebny. Dla istniejących pakietów GHCR nadaj repozytorium uprawnienia Actions do pakietu. Widoczność pakietów ustaw jawnie w GHCR — workflow nie zmienia jej automatycznie.

```sh
git tag v1.0.0
git push origin v1.0.0
```

Obrazy `linux/amd64`:

- `ghcr.io/<owner>/<repo>-backend:1.0.0`
- `ghcr.io/<owner>/<repo>-frontend:1.0.0`
- `ghcr.io/<owner>/<repo>-imaging-ai:1.0.0`

Każdy ma też `sha-<pełny-SHA>`. Nazwy owner/repo są zamieniane na małe litery. Nie tworzymy ruchomego `latest`. SemVer określa tag Git; nie zmienia wersji Maven/npm projektu.

Wszystkie obrazy są najpierw budowane lokalnie na jednym runnerze. Backend: JVM i obecność JAR; frontend: strona oraz fallback SPA; Python: importy dekoderów i `/health`. Następnie wszystkie obrazy przechodzą Trivy i otrzymują SBOM. Dopiero wtedy następuje logowanie i push. Rejestr nie zapewnia transakcji trzech obrazów: błąd publikacji może pozostawić część tagów. Tylko udany workflow i trzy wpisy w `digests.txt` oznaczają kompletne wydanie. Po awarii ponów workflow dla tego samego tagu; nie przenoś tagu na inny commit.

Frontend nasłuchuje na **8080**, obsługuje SPA i przekazuje `/api/` do `backend:8080`. Wymaga sieci Dockera z usługą/aliasem `backend`; nginx rozwiązuje nazwę przez DNS Dockera. Buforowanie proxy jest wyłączone dla SSE. Backend nasłuchuje na **8080**, imaging na **8090**. Wszystkie procesy działają bez root. Dane bazy, Keycloak, MinIO, Orthanc i tokeny przekazuj przy uruchamianiu przez zmienne; nigdy przez argumenty builda. Lokalny Compose pozostaje środowiskiem developerskim i nie jest automatycznie wdrażany.

Pełne E2E logowania, zgód pacjent–lekarz i segmentacji nie jest częścią tego pipeline. Testy nie pobierają wag modeli. Obraz imaging instaluje dodatek segmentacji, więc jego build może być duży.

Narzędzia są bezpłatne; dla prywatnego repo monitoruj limit runnerów, cache, artefaktów i GHCR. Korzystamy ze standardowych runnerów Ubuntu, krótkiej retencji i anulowania poprzedniego CI tego samego PR.
