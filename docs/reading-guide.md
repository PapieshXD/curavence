# Zrozum wynik

Wspólny czytnik dokumentów, opisów obrazowych i wyników laboratoryjnych. Oryginał
pozostaje dostępny. Definicje pochodzą wyłącznie z katalogu, a AI wybiera cytaty
i proponuje pytania do lekarza. Osobiste przypomnienia nie zmieniają planu lekarza.

## Uruchomienie i wyłączenie

Backend: `READING_GUIDE_ENABLED=true` (domyślnie). `false` wyłącza nowe endpointy
oraz interaktywną warstwę. Istniejące odczytane sekcje i oryginalne pliki pozostają
dostępne. Migracja V34 dodaje nullable powiązanie opisu oraz tabelę przypomnień;
nie zmienia danych istniejących badań. Przy wycofaniu funkcji ustaw flagę na false,
pozostawiając migrację i zapisane przypomnienia.

## Przegląd słownika przed publikacją

`backend/src/main/resources/medical-glossary.json` zawiera **100 roboczych haseł**.
Żadne z nich nie jest przedstawiane jako zatwierdzone. Początkowo czytnik może
więc pokazać komunikat o braku dostępnych zatwierdzonych objaśnień.

Lekarz lub uprawniony redaktor medyczny musi dla każdego hasła:

1. Sprawdzić prostą definicję, aliasy i odmiany oraz kategorię badania.
2. Zastąpić ogólny odsyłacz „Materiały do przeglądu” konkretnymi źródłami
   wspierającymi definicję. Nie kopiować obszernych fragmentów źródła.
3. Sprawdzić wieloznaczne skróty w `contextualAliases`. Backend aktywuje je tylko
   w rozpoznanej kategorii badania; przy nieznanej kategorii pozostają zwykłym tekstem.
4. Dopiero po rzeczywistym przeglądzie ustawić `reviewStatus: APPROVED`,
   `reviewedAt` na datę ISO i `reviewedBy` na identyfikator recenzenta.
5. Podnieść wersję katalogu, uruchomić testy i ponownie uruchomić backend.

Nie należy zatwierdzać haseł skryptem ani traktować powyższych materiałów jako
potwierdzenia przeglądu. Katalog nie zawiera indywidualnej interpretacji wyniku.

## Kontrakty

- `GET /api/reading-guide/config`: stan flagi funkcji.
- `GET /api/documents/{id}/reading-guide`: oryginalny tekst w blokach, wersja SHA-256,
  wersja słownika, zatwierdzone definicje i zakresy UTF-16 względem tekstu bloku.
- `POST /api/documents/{id}/assistant-summary`: `points`, `recommendations`, `questions`,
  wersja źródła/generatora/modelu i zakres analizy. Każdy element wskazuje blok i cytat.
- `GET/POST /api/personal-reminders`, `PUT /api/personal-reminders/{id}`: tylko właściciel
  w widoku pacjenta. Utworzenie tego samego przypomnienia zwraca istniejący rekord.
- `GET/PUT /api/imaging-studies/{id}/report-document`: jawne powiązanie opisu. Zmiana
  wyłącznie przez właściciela; odczyt wymaga osobnego dostępu do obu dokumentów.

Podsumowania nie są przechowywane w pamięci podręcznej ani bazie. Każde wywołanie
ponawia autoryzację i odczyt. Frontend odrzuca podsumowanie o innej wersji źródła,
anuluje żądania przy zmianie badania/roli i odświeża czytnik po rewizji realtime.
Backend odrzuca brakujące/nieprawidłowe cytaty, parafrazy ustaleń, urwane zdania,
terminy nieobecne w cytacie i nieprawidłową strukturę odpowiedzi. To walidacja
zgodności ze źródłem, nie gwarancja medycznej poprawności dokumentu lub doboru treści.

Przypomnienia nie przeliczają terminów względnych. Pacjent wybiera datę i zatwierdza
treść. Archiwizacja źródła ukrywa przypomnienie. Pytanie do wizyty zawiera edytowalny
cytat; przejście do formularza niczego nie zapisuje.

## Weryfikacja

- Frontend: `npm run build` i `npm test -- --watch=false`.
- Backend: `mvnw.cmd -q -Dtest=ReadingGuideServiceTest,ResultAssistantServiceTest,ReadingActionsPostgresTest test`.
  Test PostgreSQL wymaga `TEST_POSTGRES_URL`; tworzy i usuwa wyłącznie własne losowe
  schematy `reading_test_*`. Bez tej zmiennej jest pomijany.
- `tmp/reading-guide-qa.mjs`: lokalny scenariusz Playwright, korzystający z już
  dostępnej instalacji Playwright i Chrome. Zapisuje dowody do katalogu TEMP.
  Czyta prawdziwy dokument; karta definicji, zalecenia i zapis przypomnienia są
  sprawdzane na izolowanych odpowiedziach testowych. Nie publikuje roboczego słownika.
  Historia zdarzeń realtime jest izolowana, ponieważ odtworzenie dawnych zdarzeń
  usunięcia/cofnięcia dostępu w obecnym kliencie może przenosić użytkownika na Start.

Przed publikacją pozostaje przegląd medyczny treści oraz odbiór z pacjentami,
w tym osobą starszą i osobą o małej znajomości terminologii. Automatyczne testy
klawiatury nie zastępują przeglądu z rzeczywistym czytnikiem ekranu.
