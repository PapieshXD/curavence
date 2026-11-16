# Gotowość do codziennego użycia

## Działanie

- Wizyty i Badania pozwalają przygotować wybrany pakiet: domyślnie skrót PDF,
  opcjonalnie ZIP zawierający skrót, spis i niezmienione oryginały.
- Konto prowadzi do wyboru danych; nie nazywa tej operacji pełną kopią konta.
- Limit: 50 dokumentów i 250 MiB oryginałów w ZIP. Pełne serie DICOM/NIfTI
  są pobierane osobno; ich opisy PDF można dołączyć.
- Przed eksportem pacjent widzi zakres i potwierdza utworzenie kopii.
  Robocze plany lekarza i wyjaśnienia AI nie są eksportowane.
- PDF zachowuje rozróżnienie deklaracji pacjenta i zatwierdzenia lekarza.
  Font DejaVu Sans i jego licencja są dołączone do aplikacji; generator nie pobiera zasobów z sieci.
- Zmiana lub archiwizacja źródła unieważnia pakiet przy pobieraniu.
  Wygenerowane pliki są prywatne i dostępne przez 24 godziny.

Wyszukiwanie obejmuje nazwy, placówkę, nazwę pliku i odczytany tekst, po ograniczeniu
zakresu aktywnymi uprawnieniami. Pokazuje fragmenty jako zwykły tekst, bez wykonywania HTML.
Filtry dat, kategorii i odczytanej treści zostają zachowane po powrocie ze szczegółów.
Starsze teksty są indeksowane w tle, po 20 dokumentów na cykl. Po awarii magazynu ponawia próbę po 15 minutach. Brak odczytanego tekstu
lub brak gotowego indeksu jest jawnie opisany. Nie jest to nowy silnik OCR.

Identyczny SHA-256 powoduje podpowiedź wyłącznie dla dokumentów tego samego pacjenta.
Przesłane pliki pozostają osobnymi rekordami; użytkownik może otworzyć starszy dokument
lub zachować oba. System nie scala ani nie usuwa ich automatycznie.

Edycja tytułu, daty i dozwolonej kategorii PDF zapisuje historię metadanych i wymaga
aktualnej rewizji. Nie modyfikuje oryginału, parametrów laboratoryjnych ani podpisanego opisu.
Zmiana kategorii nie przełącza dokumentu na inny rodzaj przetwarzania, np. zapis EKG.

Przywrócenie z archiwum jest dostępne tylko właścicielowi i wymaga potwierdzenia listy
aktualnych zgód. Gdy lista zmieni się między podglądem a zapisem, serwer zwraca 409.
Oryginał musi istnieć w magazynie. Zapamiętany stan przetwarzania jest przywracany bez
ponownego uruchamiania analiz. Starsze dokumenty bez zapamiętanego stanu otrzymują READY
wyłącznie dla zachowanego oryginału; informacje o odczycie pozostają w istniejących metadanych.

## API

Wszystkie operacje wymagają zalogowania; operacje na dokumentach używają `X-Workspace-Role`.
Eksport i przywracanie są dostępne wyłącznie w widoku pacjenta dla własnych danych.

- `GET /api/library/config`: `searchEnabled`, `exportsEnabled`.
- `POST /api/exports`: `requestKey` UUID, opcjonalne `visitId`, jawne `studyIds`,
  `includePlan`, `includeProgress`, `includePreviousPlan`, `format` PDF/ZIP, `confirmed`.
  Ten sam klucz i zakres zwracają to samo zadanie; zmieniony zakres z tym samym kluczem zwraca 409.
- `GET /api/exports/{id}`: QUEUED, GENERATING, READY, FAILED, EXPIRED lub INVALIDATED.
- `GET /api/exports/{id}/download`: ponowna autoryzacja i weryfikacja wersji źródeł;
  odpowiedź `no-store`. Brak pliku lub niespójne oryginały nie tworzą pozornie kompletnego ZIP.
- `GET /api/documents/search`: `query`, `category`, `from`, `to`, `text` all/yes/no,
  `page`, `pageSize` (domyślnie 25, maksymalnie 100), `archived`.
- `PUT /api/documents/{id}`: istniejące `title`, `performedAt` oraz `typeCode`, `revision`.
  Starsze wywołania pozostają zgodne; nowy interfejs zawsze przesyła rewizję.
- `GET /api/documents/{id}/metadata-history`: historia metadanych właściciela.
- `GET /api/documents/{id}/restore-preview`: rewizja i zgody obejmujące przywrócony dokument.
- `POST /api/documents/{id}/restore`: `revision`, `grantIds`, `confirmed`.
- Odpowiedź przesłania dokumentu ma dodatkowe `duplicateIds`.

## Konto i logowanie

`GET /api/account/settings` sprawdza dostępność skonfigurowanej konsoli konta.
Interfejs otwiera ją w osobnej karcie, pokazuje niedostępność i umożliwia ponowienie.
Adres pochodzi wyłącznie z konfiguracji. W produkcji wymagany HTTPS;
HTTP jest dozwolone dla lokalnego localhost/127.0.0.1.

Zmiana hasła i konfiguracja aplikacji uwierzytelniającej odbywają się w Keycloak.
Logowanie Curavence obsługuje dodatkowe opcjonalne `otp` (6–8 cyfr), przekazywane jako
`totp` do istniejącego dostawcy logowania. Hasła i klucze OTP nie są zapisywane w Curavence.
Przycisk „Wyloguj się” w Koncie korzysta z istniejącego unieważniania sesji.
Nie dodano własnego systemu haseł, passkeys ani trwałego usuwania kont.

## Konfiguracja i wdrożenie

- `EXPORTS_ENABLED=true` i `DOCUMENT_SEARCH_ENABLED=true` domyślnie.
  Flagi działają niezależnie. Po wyłączeniu wyszukiwania pozostaje dotychczasowa lista
  dokumentów, również z możliwością wyboru do eksportu, jeśli eksport jest włączony.
- `KEYCLOAK_ACCOUNT_CONSOLE_URL`: jawny adres konsoli; lokalnie
  `http://localhost:8081/realms/med-node/account/`.
- Migracja V37 dodaje rewizję, historię, indeks tekstu, stan archiwizacji i zadania eksportu.
  Nie zmienia wcześniejszych migracji i nie usuwa oryginałów.
- Przed wdrożeniem wykonać kopię bazy. Najpierw backend i migracja, następnie frontend.
  Nowe widoki są ładowane na żądanie. Poszczególne flagi można wyłączyć przy odbiorze.
- Przy wycofaniu zachować migrację i dane. Wyłączenie eksportu nie zatrzymuje usuwania
  jego wygasłych plików. Wpisy audytu i metadane zadań pozostają; treść snapshotu jest
  czyszczona po wygaśnięciu.
- Zadania eksportu mają blokadę pobrania z kolejki na poziomie PostgreSQL.
  Przerwane generowanie po 15 minutach kończy się błędem; ponowienie tworzy nowy pakiet.
- Przełączniki `ralek.exports.worker-enabled` i `ralek.search.worker-enabled` służą
  izolowaniu testów. W zwykłym wdrożeniu pozostają włączone.
- Kontrolować błędy migracji, nieudane zadania i stan magazynu. Nie dodawać treści wyników
  i zgłoszeń do analityki ani logów diagnostycznych.

## Odbiór

Backend: `mvnw.cmd -q test`. Testy rzeczywistego PostgreSQL wymagają `TEST_POSTGRES_URL`
oraz opcjonalnie `TEST_POSTGRES_USER` i `TEST_POSTGRES_PASSWORD`. Tworzą i usuwają własne
schematy `readiness_test_*`; magazyn plików w tych testach jest izolowany.
Frontend: `npm test -- --watch=false` i `npm run build`.

Przed publikacją przeprowadzić odbiór z rzeczywistym czytnikiem ekranu i powiększeniem
przeglądarki 200% oraz pilotaż z pacjentami: wybór pakietu, odnalezienie fragmentu wyniku,
poprawienie metadanych i przywrócenie omyłkowo zarchiwizowanego dokumentu.
Osobno pozostaje medyczny przegląd słownika „Zrozum wynik”.
