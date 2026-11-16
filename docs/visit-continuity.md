# Od ostatniej wizyty

MVP łączy wskazaną przez pacjenta poprzednią wizytę, zgłoszenia realizacji,
wybrane wyniki i omówienie przez lekarza. Nie interpretuje wyników medycznych.
Nawigacja pacjenta pozostaje: Start, Badania, Wizyty, Udostępnianie, Konto.

## Przebieg i zakres zgody

1. Pacjent tworzy nową sprawę albo wybiera konkretną poprzednią wizytę z zatwierdzonym planem.
2. Osobno wybiera dołączenie poprzedniego planu wraz ze zgłoszeniami realizacji.
   Dokumenty zaznacza osobno. Brak zgody na plan ukrywa przed lekarzem również jego identyfikator.
3. Przed udostępnieniem sprawdza kontekst, dokumenty, odbiorcę i termin dostępu.
4. Pacjent zgłasza wykonanie lub trudność; data wykonania jest opcjonalną deklaracją.
   Wykonanie zadania nie oznacza jego omówienia przez lekarza.
5. Powiązanie wyniku z zadaniem nie rozszerza zgody. Nowy wynik udostępnia się
   osobnym potwierdzeniem zakresu w pakiecie wizyty.
6. Lekarz oznacza zadanie jako omówione w ramach aktywnej zgody na dany pakiet.
   Zmiana zgłoszenia pacjenta usuwa wcześniejsze oznaczenie omówienia.

Lekarz może otrzymać poprzedni zatwierdzony plan przez nowy pakiet bez dostępu
do poprzedniej wizyty jako całości. Zgłoszenia realizacji są aktualizowane na żywo;
dokumenty pozostają ograniczone do jawnie wybranego zakresu nowego pakietu.
Przy cofnięciu lub wygaśnięciu zgody lekarz traci odczyt i możliwość oznaczania
omówienia oraz dostęp do powiadomień powiązanych z pakietem. Pacjent zachowuje plan.
Archiwalne dokumenty nie mają aktywnych odsyłaczy. Ich dawne powiązania pacjent
może usunąć z zadania; lekarz nie otrzymuje identyfikatorów ukrytych źródeł.

## API i migracje

- `POST /api/visits`, `PUT /api/visits/{id}`: dodatkowe `previousVisitId` i
  `includePreviousPlan`. Domyślnie brak powiązania i brak udostępnienia planu.
- `GET /api/visits`: dodatkowe `previousPlan`, `newStudyIds` oraz pola realizacji
  i omówienia z autorami, datami i rewizją. Nowość dokumentu jest porównaniem
  zawartości jawnie wskazanych pakietów, a nie oceną daty lub treści medycznej.
- `PUT /api/visits/{id}/actions/{actionId}/progress`: `status` (`TODO`, `DONE`,
  `DIFFICULTY`), opcjonalne `performedOn`, `note`, `obstacle`, `studyIds`, `revision`.
  Przeszkody: `NO_APPOINTMENT`, `COST`, `UNCLEAR`, `OTHER`.
- `PUT /api/visits/{id}/actions/{actionId}/discussion`: `discussed`, `revision`.
  `id` jest pakietem, przez który lekarz otrzymał zgodę na bieżący lub poprzedni plan.
- `PUT /api/visits/{id}/scope`: `studyIds`, `confirmed: true`; wyłącznie właściciel,
  aktywna zgoda, własne niearchiwalne dokumenty. Rozszerzenie jest rejestrowane w audycie.
- `GET /api/visits/notifications`: do 100 najnowszych dostępnych powiadomień.
- `PUT /api/visits/notifications/{id}/read`: oznaczenie przeczytania przez odbiorcę.

Wymagane uwierzytelnienie i `X-Workspace-Role`. Konflikt rewizji zwraca 409;
klient odświeża widok przed ponowieniem. Ponowienie nie dodaje powiadomienia.
Zachowano starszy endpoint zakończenia zadania i pole `completed`.
V35 migruje stare wykonane zadania do `DONE`, bez dopisywania dat i autorów.
V36 dodaje zdarzenia `visits.changed` bez treści medycznej.
Nie zmieniać ani nie usuwać wcześniejszych migracji.

Powiadomienia w aplikacji: zatwierdzenie planu, aktualizacja realizacji,
trudność oraz termin. Harmonogram sprawdza terminy co minutę według Europe/Warsaw:
raz dzień wcześniej i raz w dniu terminu, bez codziennych powtórzeń zaległości.
Brak gwarancji bieżącej odpowiedzi lekarza jest widoczny przy zgłoszeniu i w centrum.

## Weryfikacja i uruchomienie

- Backend: `mvnw.cmd -q test`.
- Test PostgreSQL `VisitContinuityPostgresTest`: ustawić `TEST_POSTGRES_URL`,
  `TEST_POSTGRES_USER`, `TEST_POSTGRES_PASSWORD`. Test tworzy i usuwa wyłącznie
  własne schematy `visit_test_*`; obejmuje zgodę, konflikty, powiadomienia,
  rzeczywistą migrację starych danych oraz triggery.
- Frontend: `npm test -- --watch=false`, `npm run build`.
- Przed produkcją wykonać kopię bazy, wdrożyć backend z migracjami V35/V36,
  następnie frontend. Starszy frontend nadal może używać pola `completed`.
  Przy wycofaniu interfejsu zachować migracje i zapisane dane.
- Sprawdzić logi migracji, działanie harmonogramu i błędy odczytu/uprawnień;
  nie zapisywać treści zgłoszeń ani wyników w analityce produktu.

Automatyczny test interfejsu może używać izolowanych odpowiedzi API. Taki test
nie stanowi potwierdzenia pełnego wdrożenia wraz z dostawcą logowania i magazynem plików.
Odbiór z rzeczywistym czytnikiem ekranu i powiększeniem przeglądarki 200% wymaga
osobnej weryfikacji, podobnie jak przegląd medyczny słownika „Zrozum wynik”.

## Pilotaż i dalsza mapa

Pilotaż: 5 niezależnych lekarzy i 10 dorosłych pacjentów, w tym osoby starsze
i o małej znajomości terminologii. Mierzyć ręcznie czas wykonania zadania i liczbę
potrzebnych wyjaśnień, zapisując wyłącznie pseudonim uczestnika, czas i wynik próby.
Cele: 8/10 pacjentów samodzielnie przypina wynik; 4/5 lekarzy znajduje nowe wyniki
i niezrealizowane kroki w minutę; uczestnicy rozróżniają deklarację i omówienie.

Po pilotażu, jako osobne etapy: eksport pakietu PDF poza Curavence,
„Czy dobrze rozumiem plan?”, porównywalne wyniki w czasie, propozycje duplikatów,
delegowany dostęp bliskiej osoby i AI wspierające przygotowanie z cytatami.
Integracje z IKP, urządzeniami i gabinetami oraz konta dzieci pozostają poza MVP.
