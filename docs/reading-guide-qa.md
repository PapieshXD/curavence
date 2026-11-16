# Zrozum wynik — wynik weryfikacji

## Środowisko

2026-11-01, lokalny frontend `http://127.0.0.1:4202`, backend Spring Boot i PostgreSQL 17.
Chrome przez istniejącą instalację Playwright. Browser plugin not available.
Rozmiary okna: 1440×1000, 720×500, 360×780.

## Sprawdzone zachowania

| Kontrola | Wynik | Dowód |
| --- | --- | --- |
| Tożsamość strony | OK | Trasa `/documents/{id}`, tytuł Curavence |
| Strona nie jest pusta | OK | Nagłówek Zrozum wynik, 21 bloków rzeczywistego dokumentu |
| Nakładka błędu frameworka | Brak | Zrzuty pulpitu i telefonu |
| Konsola | OK | Brak błędów JS w scenariuszu |
| Oryginał | OK | Pobranie rzeczywistego PDF jednym przyciskiem |
| Pojęcie | OK | Otwarcie Enterem, fokus na zamknięciu, Escape i powrót fokusu |
| Telefon | OK | Panel przy dolnej krawędzi, widoczny przycisk zamknięcia, brak przewijania w poziomie |
| Cytat | OK | Przewinięcie i fokus na bloku źródłowym |
| Pytanie | OK | Edytowalny szkic z pojęciem i cytatem, bez automatycznego zapisu |
| Przypomnienie | OK | Zapis, ponowne otwarcie istniejącego rekordu bez dodatkowego POST, zakończenie |
| Badanie obrazowe | OK | Wybór dokumentu, zapis powiązania, pokazanie powiązanego czytnika |
| Start | OK | Najbliższe osobiste przypomnienie w karcie działania |

Wywołania odczytu dokumentu i pobrania oryginału używały rzeczywistego backendu.
Definicja, podsumowanie, zalecenie, zapis przypomnienia i powiązanie opisu w teście
przeglądarkowym używały izolowanych odpowiedzi API. Żadne robocze hasło nie zostało
opublikowane i scenariusz nie zapisał sztucznych zaleceń do danych pacjenta.

Osobno wykonano rzeczywiste wywołanie modelu przez endpoint podsumowania. Zwrócił
HTTP 200 i puste listy dla wybranego dokumentu. Nie jest to potwierdzenie jakości
wyboru najważniejszych treści we wszystkich rodzajach badań.

## Testy automatyczne

- Frontend: 38 plików, 71 testów zaliczonych.
- Backend: 101 testów, 100 zaliczonych, 1 pominięty w istniejącym
  `StudyAnalysisMigrationTest`; bez błędów.
- Testy rzeczywistego PostgreSQL: równoległe tworzenie tego samego przypomnienia,
  edycja/zakończenie, izolacja właściciela, archiwizacja źródła, obcy pacjent,
  jawne powiązanie i usunięcie powiązania, osobne uprawnienia do obrazu i opisu,
  natychmiastowe cofnięcie dostępu lekarza.
- Testy źródła i AI: Unicode i zakresy UTF-16, zachowanie wszystkich znaków,
  granice słów, dłuższe frazy, pierwsze wystąpienie, kategoria skrótu, blokada
  roboczych haseł, ponowna autoryzacja, flaga funkcji, błędny JSON, zmiana
  przeczenia/niepewności, fałszywy termin, skrócenie dokumentu o 100000 znakach.
- Build produkcyjny: przechodzi. Ostrzeżenia: przekroczenie budżetu początkowego
  pakietu oraz zależności CommonJS obecne w aplikacji.

## Ograniczenia odbioru

100 haseł ma status DRAFT i wymaga rzeczywistego przeglądu medycznego przed
publikacją. Nie przeprowadzono badania użyteczności z pacjentami ani testu
rzeczywistym czytnikiem ekranu. Rozmiar 720×500 sprawdza węższy układ desktopowy;
nie zastępuje testu rzeczywistego powiększenia przeglądarki 200%.

Historia synchronizacji realtime została odizolowana w scenariuszu przeglądarkowym:
odtwarzanie wcześniejszych zdarzeń usunięcia lub cofnięcia dostępu w istniejącym
kliencie może powodować powrót na Start przy bezpośrednim otwarciu adresu.
Nie zmieniano mechanizmu realtime w ramach tej funkcji.

Lokalne dowody Playwright są w katalogu TEMP: `curavence-reading-qa-result.json`,
`curavence-reading-real.png`, `curavence-reading-desktop-qa.png`,
`curavence-reading-mobile-qa.png`. Zrzuty z kartą pojęcia przedstawiają dane testowe.
