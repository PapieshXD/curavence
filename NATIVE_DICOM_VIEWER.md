# Natywny viewer DICOM Curavence

Frontend nie osadza już OHIF w ścieżce użytkownika. Własny viewer pobiera metadane i klatki przez krótkotrwałą, autoryzowaną sesję DICOMweb, a następnie renderuje je lokalnie w przeglądarce przez Cornerstone3D. Orthanc pozostaje archiwum DICOM; kontener OHIF może być utrzymany wyłącznie jako opcjonalne narzędzie zgodności i nie jest wymagany przez ekran badania.

## Przepływ danych

1. Frontend tworzy sesję viewer dla konkretnego badania i bieżącego użytkownika.
2. Backend ponownie sprawdza dostęp pacjenta lub personelu i zwraca ograniczony czasowo adres proxy DICOMweb.
3. Viewer pobiera listę serii i instancji przez QIDO-RS. Wymagane tagi geometrii są żądane jawnie, ponieważ część serwerów nie dołącza ich do `includefield=all`.
4. Piksele są pobierane przez WADO-RS i dekodowane w workerze należącym do aplikacji. Dzięki temu build nie zależy od wewnętrznej ścieżki workera paczki ani od serwera OHIF.
5. Pomiary i adnotacje są zapisywane przez API Curavence razem z identyfikatorem serii, SOP Instance UID i Frame of Reference UID.

## Zakres funkcjonalny

| Obszar | Implementacja |
| --- | --- |
| Serie i stos przekrojów | Lista serii, środkowy przekrój na start, suwak, kółko myszy, poprzedni/następny |
| Obraz | Automatyczne VOI z percentyli, window/level, zoom, pan, inwersja, odbicie H/V, obrót, reset, pełny ekran |
| Pomiary | Długość, kąt, elipsa ROI, prostokąt ROI, punkt/probe, strzałka z opisem, lupa |
| Odtwarzanie | Cine z prędkościami 4, 8, 12 i 20 klatek/s |
| Rekonstrukcje | MPR axial/coronal/sagittal ze wspólnymi liniami odniesienia; 3D tylko dla serii potwierdzonych przez walidator geometrii |
| Widok dla pacjenta | Plansza wszystkich przekrojów, pojedynczy przekrój, raport, objaśnienia krok po kroku |
| Segmentacja | Maska nałożona na całą planszę i pojedynczą klatkę, regulacja krycia, legenda; pliki źródłowe pozostają dostępne do pobrania |
| Bezpieczeństwo | Kontrola dostępu przy utworzeniu sesji i każdym żądaniu proxy, krótki TTL, brak poświadczeń Orthanc w przeglądarce |
| Odporność | Gdy generator uproszczonej planszy jest niedostępny, ekran automatycznie przechodzi do natywnego renderowania DICOM zamiast pokazywać pustą stronę |

## Granica zgodności z OHIF

Viewer odtwarza główny workflow używany w Curavence, ale nie kopiuje całego ekosystemu rozszerzeń OHIF. Nie są obecnie częścią produktu: hanging protocols konfigurowane przez administratora, DICOM RT, mikroskopia whole-slide, federacja wielu PACS i marketplace rozszerzeń. Jeśli któryś z tych modułów stanie się wymaganiem, powinien zostać dodany jako osobny, testowalny moduł zamiast imitowania nieużywanej funkcji w UI.

## Uruchomienie i weryfikacja

Frontend ładuje moduły obrazowania leniwie; zwykłe ekrany aplikacji nie pobierają dużych bibliotek ani kodeków. Produkcyjny build musi zawierać osobny chunk `dicom-decoder-worker`. Test przeglądarkowy powinien potwierdzić odpowiedzi 200 dla QIDO/WADO, niepuste płótno, zmianę aktywnego narzędzia, start/stop cine oraz trzy płótna MPR.
