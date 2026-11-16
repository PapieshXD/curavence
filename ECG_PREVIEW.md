# Podgląd EKG i analiza AI

Istniejące badania są odczytywane na żądanie z oryginału w MinIO. Nie wymagają ponownego przesłania ani migracji danych. Endpointy `/api/documents/{id}/ecg` i `/ecg/questions` sprawdzają dostęp do badania przed odczytem pliku.

## Obsługiwane źródła

| Źródło | Podgląd |
|---|---|
| CSV, TSV, TXT | Kolumny liczb z opcjonalnymi nagłówkami; separator przecinek, średnik, tabulator lub spacje. Przecinki dziesiętne z separatorem średnikowym. |
| JSON | Tablica próbek albo `samples`, `signal`, `data`, `val`; kanały w `channels`/`leads`; `fs`/`samplingRate`, `unit`. |
| XML | Jawne liczby w `samples`, `values`, `digits`, `data` wewnątrz `lead`/`channel`/`leaddata`/`sequence`. Skompresowane formaty producentów wymagają konwersji. |
| MAT | Numeryczny wektor lub macierz `val`, `data`, `signal`, `samples`; opcjonalne `fs`, `unit`. Wariant HDF5 7.3 wymaga konwersji. |
| EDF/BDF | Kanały, kalibracja i częstotliwość z nagłówka przez pyEDFlib. |
| DICOM Waveform | Grupy WaveformSequence i kalibracja kanałów przez pydicom. DICOM obrazowy nie jest sygnałem. |
| ZIP WFDB | Jednosegmentowy HEA wraz z plikami sygnału wskazanymi w nagłówku, przez WFDB. Sam DAT/ATR nie wystarcza. |
| ZIP z eksportami | Obsługiwane pliki sygnału, kanały opisane nazwą pliku źródłowego. |
| PNG, JPEG, BMP, GIF, TIFF, WebP | Obraz z pierwszej klatki/strony. Bez odzyskiwania próbek z pikseli. |
| PDF | Dotychczasowy podgląd PDF i analiza wyodrębnionego tekstu. Skany PDF nadal wymagają OCR. |
| SCP, GDF, binarne ECG, warianty producentów | Oryginał do pobrania i komunikat o wymaganej konwersji; brak pozornego wykresu. |

## Kalibracja i ograniczenia

- Bez Hz wykres używa numerów próbek. Bez jednostki pokazuje wartości źródłowe; nie zakłada mV ani 250/500 Hz.
- Kolumna czasu musi jawnie wskazywać `s`, `sec` lub `ms`, mieć rosnące wartości i równomierny krok, aby ustalić Hz.
- Kalibrację użytkownika stosuje się tylko w brakujących polach, na czas widoku i rozmowy. Nie modyfikuje oryginału.
- Odczyt: maksymalnie 25 MB, 64 kanały i 2 miliony wartości. Podgląd obejmuje cały odczytany zapis, do limitu 2 milionów wartości łącznie. Statystyki dotyczą całego odczytu; osobna kolumna zawiera pomiary widocznego fragmentu.
- Wykres pokazuje przerwy danych i zachowuje ekstrema przy redukcji punktów. Osie amplitudy są dopasowywane do okna każdego kanału, nie są kalibracją papieru EKG.
- ZIP: maksymalnie 64 wpisy i 25 MB po rozpakowaniu. Niedozwolone są ścieżki wychodzące poza archiwum, symlinki i zewnętrzne odwołania WFDB. XML nie rozwija encji.

## AI

Przycisk „Analizuj zapis z AI” korzysta z istniejącego lokalnego klienta Ollama. Serwer ponownie odczytuje autoryzowany oryginał i wylicza kontekst; przeglądarka nie dostarcza modelowi własnych statystyk. Model otrzymuje liczbę próbek, kalibrację, min/max, średnią, odchylenie i braki danych. Otrzymuje również algorytmiczne podsumowanie P–QRS–T aktualnie wybranego kanału i fragmentu: mediany, zakresy, liczby pomiarów, kompletność i ograniczenia. Dostępne są osobne objaśnienia dla pacjenta i diagnosty. AI wyjaśnia obliczone wyniki, nie wyznacza samodzielnie granic załamków ani diagnozy. Zmiana fragmentu lub odbiorcy czyści poprzednią rozmowę, aby nie mieszać kontekstów.

Dla obrazów przekazywany jest PNG do modelu obsługującego obraz (lokalnie qwen3.5:4b). Analiza dotyczy widocznego tekstu i czytelności, nie pomiarów pikselowych. Awaria modelu jest pokazywana jako błąd, bez zastępczej fikcyjnej odpowiedzi.

Zależności dekoderów są zapisane w `imaging-ai-service/pyproject.toml`. Przy odtwarzaniu środowiska należy przebudować obraz serwisu: `docker compose build imaging-ai-service`, następnie `docker compose up -d imaging-ai-service`.

Dokumentacja użytych adapterów: [WFDB](https://wfdb.readthedocs.io/en/latest/io.html), [SciPy MAT](https://docs.scipy.org/doc/scipy/reference/generated/scipy.io.loadmat.html), [pydicom Waveforms](https://pydicom.github.io/pydicom/stable/guides/user/working_with_waveforms.html), [Ollama obrazy](https://github.com/ollama/ollama/blob/main/docs/capabilities/vision.mdx).

## Automatyczny widok

Widok domyślnie dobiera okno na podstawie odstępu wyraźnych szczytów (wyłącznie do kadrowania), a przy braku stabilnego oszacowania pokazuje 6 sekund przy znanym Hz lub 1500 próbek przy nieznanym Hz. Amplituda dopasowuje się do min/max fragmentu bez przycinania.

Oddzielne `displaySamples` mogą zawierać wynik filtru notch Q=35, stosowanego dwukierunkowo przez SciPy tylko przy dominującej, wąskopasmowej i trwałej składowej widma. Przy znanym Hz ograniczamy wykrywanie do okolic 50/60 Hz. Przy nieznanym Hz częstotliwość jest wyrażona w cyklach na próbkę i nie jest automatycznym ustaleniem częstotliwości próbkowania. Nie filtrujemy krótkich zapisów ani danych z lukami. Przycisk „Pokaż oryginał” przełącza na nienaruszone `samples`. Oznaczenie filtracji jest widoczne poza zwiniętymi ustawieniami. Filtracja może zmieniać morfologię i brzegi zapisu; nie nadaje podglądowi walidacji diagnostycznej. Statystyki AI pozostają liczone z oryginału.

Implementacja: [SciPy iirnotch](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.iirnotch.html), [SciPy filtfilt](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.filtfilt.html).

## Nawigacja i pomiary algorytmiczne

Przeciągnięcie wykresu przesuwa okno. Kółko zmienia powiększenie, Shift + kółko przesuwa, a kliknięcie miniatury wybiera fragment. Suwak, przyciski początku/końca i klawisze strzałek/Home/End pozwalają dotrzeć do całego odczytanego zapisu. Miniatura zachowuje ekstrema, a szczegółowy wykres używa oryginalnych lub jawnie filtrowanych próbek.

Statystyki amplitudy: minimum, maksimum, rozstęp, średnia, populacyjne odchylenie standardowe, RMS, liczba braków; cały zapis i okno są liczone z surowych próbek, niezależnie od filtru widoku. Puste okno daje brak wyniku zamiast zera.

Kalibracja jest odczytywana również z komentarzy `# fs=250`, `# unit=mV`, jednostek w nagłówkach kolumn i regularnej kolumny czasu ze wskazaniem s/ms. Konflikt częstotliwości nagłówka z czasem powoduje błąd. JSON przyjmuje dodatkowe nazwy sample_rate/sampling_frequency i regularną tablicę time z timeUnit=s/ms. Brak skali fizycznej nigdy nie jest zastępowany domyślnym Hz lub mV.

Eksperymentalne wykrywanie kandydatów na szczyty wykorzystuje `scipy.signal.find_peaks`, próg prominence 1.5*SD, odległość 0.25s przy znanym Hz lub 30 próbek bez Hz oraz dominującą polaryzację. Podaje liczbę kandydatów, medianę odstępu i współczynnik zmienności odstępów. Przy znanym Hz podaje odstęp w ms i częstość szczytów/min; nie są to zweryfikowane załamki R, tętno ani HRV. Przy lukach, stałym sygnale lub niewystarczającej liczbie szczytów wynik jest niedostępny. Metoda i ograniczenia są częścią kontekstu AI, bez przesyłania wielkich tablic próbek ani indeksów.


## Delineacja P–QRS–T

NeuroKit2 0.2.12: ecg_clean, ecg_peaks (bez korekty artefaktów) i ecg_delineate metodą DWT. Analiza obejmuje 10–30 sekund wybranego kanału; minimalny ciągły fragment to 6 sekund, Hz musi pochodzić z pliku lub jawnej kalibracji użytkownika (100–2000 Hz). Dane powyżej 500 Hz są resamplowane do detekcji, po czym wszystkie znaczniki wracają do indeksów oryginału. Nie zmienia to źródłowych próbek.

Oznaczane są kandydaty P, Q, R, S i T. Algorytm mierzy czas P, PR, QRS, QT, poprzedzające RR, szacowane HR oraz QTc Fridericii. QTcF = QT / (RR w sekundach)^(1/3); publikowane tylko dla RR 300–2000 ms. Pomiar czasu wymaga prawidłowej kolejności granic; brak wyniku pozostaje nullem, nie zerem. Szerokie bramki poprawności nie są normami klinicznymi.

Kliknięcie znacznika lub wybór cyklu pokazuje jego pomiary i zaznacza PR/QRS/QT na wykresie, jeżeli całe granice mieszczą się w oknie. Karty pokazują medianę, min/max i liczbę pomiarów w analizowanym kontekście. QTc jest wartością skorygowaną matematycznie, nie długością bezpośrednio zaznaczaną na osi czasu. Przesuwanie/zoom powoduje ponowną analizę z opóźnieniem 500 ms i anulowaniem starego żądania.

Braki próbek, stały sygnał, brak Hz lub zbyt mało stabilnych cykli wstrzymują pomiary. Mediana korelacji cykli z szablonem jest miarą powtarzalności technicznej, nie prawdopodobieństwem poprawności klinicznej. Niska powtarzalność może też wynikać ze zmiennej morfologii. Wyniki są oszacowaniami do weryfikacji, bez walidacji diagnostycznej; nie rozpoznajemy arytmii, zmian ST, osi serca ani HRV.

Testy obejmują sygnał syntetyczny, brak kalibracji, luki, krótkie/stałe dane, granice okna, przeliczenie indeksów po resamplingu i wzór QTc. Podgląd nakładek zweryfikowano na danych syntetycznych, bez zapisywania ich w dokumentacji pacjenta. Dokumentacja algorytmu: [NeuroKit2 ECG](https://neuropsychology.github.io/NeuroKit/functions/ecg.html).


## Automatyczny tryb bez Hz

Brak Hz uruchamia analizę kształtu bez ręcznej kalibracji. Algorytm wyznacza odstęp kandydatów szczytów, normalizuje go do umownego cyklu i używa go do ustawienia okien detektora. To nie jest odzyskiwanie Hz ani założenie rzeczywistego tętna. W tym trybie zwracane są tylko hipotezy PQRST oraz odległości w próbkach (timeBasis=samples); pola ms, HR i QTc pozostają puste. Taką samą informację otrzymuje AI. Widok oznacza tryb bez Hz, a osie nadal używają numerów próbek. Fragmenty z brakiem stabilnego kształtu mogą pozostać niedostępne. Automatyczny notch, jeżeli zastosowany, jest opisany w metodzie.

Ten tryb zastępuje wcześniejszą całkowitą blokadę PQRST przy braku Hz. Nie nadaje hipotetycznym granicom walidacji klinicznej. Test na lokalnym data3_dobry pozwolił pokazać kandydatów bez wpisywania częstotliwości.


## AI dla brakującego Hz

Widok bez częstotliwości automatycznie wywołuje autoryzowany POST /ecg/calibration-assessment. Backend ponownie odczytuje oryginał i przekazuje lokalnemu modelowi metadane oraz zwięzłe statystyki. AI ocenia podstawy ustalenia Hz i opisuje ewentualne hipotezy. Tekst odpowiedzi nie jest kalibracją i nie jest automatycznie stosowany do pomiarów; nie ma parsera, który zamieniałby dowolną liczbę modelu na Hz. Błąd AI nie blokuje wykresu ani analizy w próbkach.


## Czas z długości rejestracji

CSV: komentarz durationSeconds / duration_s / recording_duration_s. JSON: durationSeconds / recordingDurationSeconds. Pole oznacza pełny czas rejestracji N/fs, nie różnicę czasu ostatniej i pierwszej próbki (N-1)/fs. Częstotliwość wyliczamy jako N/duration; konflikt z jawnym Hz lub kolumną czasu przekraczający 2% jest odrzucany. Brak jednostki amplitudy pozostaje brakiem.

Widok bez Hz pozwala podać znany pełny czas nagrania i przelicza oś na sekundy. Jest to kalibracja użytkownika tylko na czas podglądu, bez zmiany oryginału. Nie należy wprowadzać arbitralnego czasu na podstawie wyglądu zapisu. AI wyjaśnia brak kalibracji i dostępne pomiary; nie utożsamia amplitudy z siłą pracy serca ani uzupełnienia Hz z walidacją HRV.


WFDB ZIP: obsługa do 64 kanałów łącznie, z zachowaniem limitu 2 mln wartości. Rekordy sortowane naturalnie (rec_2 przed rec_10). Paczka Person_01: 20 niezależnych rekordów po 2 kanały, każdy 20 s przy 500 Hz, amplituda fizyczna dekodowana przez WFDB w mV. Rekordy nie są sklejane w jeden ciągły zapis. Dla ponad 12 kanałów podgląd domyślnie pokazuje pierwszy; pozostałe dostępne w wyborze kanału.
