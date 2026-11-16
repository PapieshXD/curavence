# Podsumowanie dokumentacji pacjenta

Widok w Badaniach oraz w wybranym pakiecie Wizyty. Nie dodaje nowej pozycji nawigacji.
Źródłami są aktywne dokumenty z odczytanym tekstem, zatwierdzone plany i deklaracje realizacji.
Szkice planów nie są analizowane. Brak tekstu/OCR i ograniczenia kontekstu są jawnie pokazane.

## Działanie

`GET /api/knowledge?visitId=...` tworzy zakres i odświeża manifest źródeł. Pacjent może pominąć
visitId, otrzymując swoją dokumentację. Lekarz musi wskazać pakiet z aktywną zgodą.
Zakres lekarza obejmuje tylko dokumenty jednocześnie należące do pakietu i jego zgody,
zatwierdzony plan oraz jawnie dołączony poprzedni plan. Odczyt i odpowiedź ponownie
sprawdzają zgodę i rewizje. Dane pacjenta nie są wysyłane do CI ani modeli chmurowych.

Trwała kolejka PostgreSQL przechowuje stan QUEUED/GENERATING/READY/FAILED/INVALIDATED.
Worker co 30 sekund sprawdza do 100 najdawniej sprawdzonych zakresów i wykonuje jedno zadanie.
Zmienione źródła usuwają nieaktualne opracowania; niezmienione strony źródłowe są używane ponownie.
Przegląd obejmuje fakty z wielu źródeł, strony źródeł i sekcje zaleceń, deklaracji i rozbieżności.
Ollama wybiera pełne cytaty, a backend sprawdza ich źródła i granice. Opracowania są ekstrakcyjne:
nie są nową diagnozą ani swobodną interpretacją. Nie zapisujemy odpowiedzi jako nowych faktów.

`POST /api/knowledge/questions` przyjmuje `{question, visitId}`. Wykorzystuje indeks stron
wiki i dostępne teksty. Zwraca zweryfikowane cytaty oraz informację o ograniczeniu kontekstu.
`POST /api/knowledge/rebuild?visitId=...` zwraca 202 i aktualny stan zakresu.
Stan kolejki i strony są zwracane przez GET; UI odpytuje co 5 sekund podczas generowania,
później co 30 sekund. Nie dodano osobnych endpointów stron ani zadań.

## Konfiguracja i uruchomienie

- Migracja V38 jest dodawana przez Flyway przy starcie backendu. Nie zmieniać V1–V37.
- `PATIENT_KNOWLEDGE_ENABLED=true` włącza funkcję (domyślna konfiguracja aplikacji).
  W pilotażu lub przy wycofaniu ustawić false. Oryginały i plany pozostają dostępne.
- `PATIENT_KNOWLEDGE_POLL_MS=30000` określa odstęp workera.
- Model używa istniejących `OLLAMA_BASE_URL`, `OLLAMA_DOCUMENT_MODEL`, `DOCUMENT_CHAT_ENABLED`.
- Maksymalnie 3 próby; ręczne Przygotuj ponownie odblokowuje kolejne próby.
  Zadania przerwane restartem wracają do kolejki po 15 minutach.
- Dokument: do 12000 znaków, dokumenty razem: 60000, kontekst z wizytami: 80000.
  Treść poza limitem nie jest analizowana. To nie jest pełne OCR ani analiza DICOM.

Wersjonowanie aktualnych źródeł jest oparte o fingerprint i rewizje stron. Stare medyczne
opracowania są usuwane przy zmianie źródeł; nie udostępniamy archiwum poprzednich syntez.
Wywołania odczytu/pytania są audytowane bez treści pytań i odpowiedzi.

## Weryfikacja

Backend: `mvnw.cmd test`. Testy `KnowledgeGeneratorTest` sprawdzają cytaty i negacje.
`KnowledgePostgresTest` wymaga TEST_POSTGRES_URL, TEST_POSTGRES_USER, TEST_POSTGRES_PASSWORD;
tworzy i usuwa wyłącznie własny schemat knowledge_test_* i uruchamia pełny łańcuch migracji.
Frontend: `npm test -- --watch=false` i `npm run build`.
Testy z izolowanym modelem i API nie potwierdzają jakości konkretnego modelu Ollama.
Przed produkcją konieczny jest odbiór na docelowym modelu i przegląd medyczny przykładów.
