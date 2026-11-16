"""Experimental, bounded PQRST delineation with explicit calibration and coverage."""
import math
import warnings
import numpy as np

METHOD = 'NeuroKit2 0.2.12: ecg_clean(neurokit), ecg_peaks(neurokit), ecg_delineate(dwt)'
LIMITATION = 'Automatyczne oznaczenia wymagają weryfikacji. Nie rozpoznają chorób ani nie zastępują interpretacji EKG. Pomiar z pojedynczego kanału nie jest globalnym pomiarem 12-odprowadzeniowym.'


def unavailable(reason, start=0, end=0):
    return dict(status='unavailable', reason=reason, method=METHOD, limitation=LIMITATION,
                startSample=start, endSample=end, markers=[], beats=[], intervals=[], summary={}, quality={})


def summarize_measurements(beats):
    result = {}
    for key in ('rrMs', 'heartRateBpm', 'prMs', 'pDurationMs', 'qrsMs', 'qtMs', 'qtcFridericiaMs'):
        values = [beat['measurements'][key] for beat in beats if beat['measurements'].get(key) is not None]
        result[key] = dict(median=float(np.median(values)), min=float(min(values)), max=float(max(values)), count=len(values)) if values else None
    return result


def analyze(values, fs, start=0, count=0):
    if fs is None:
        return analyze_uncalibrated(values, start, count)
    if not math.isfinite(fs) or not 100 <= fs <= 2000:
        return unavailable('Analiza P–QRS–T obsługuje częstotliwości od 100 do 2000 Hz.')
    start = max(0, min(int(start), len(values)))
    # A minimum 10 s context prevents delineation on isolated peaks; maximum 30 s bounds cost.
    length = max(int(fs * 10), min(int(count or fs * 10), int(fs * 30)))
    end = min(len(values), start + length)
    if end - start < fs * 6:
        start = max(0, end - length)
    original = np.asarray(values[start:end], dtype=float)
    if len(original) < fs * 6:
        return unavailable('Potrzeba co najmniej 6 sekund zapisu.', start, end)
    if not np.isfinite(original).all():
        return unavailable('Fragment zawiera braki danych. Wybierz ciągły fragment; luk nie interpolujemy.', start, end)
    if np.ptp(original) < 1e-12:
        return unavailable('Stały sygnał: nie można wyznaczyć zespołów P–QRS–T.', start, end)
    try:
        import neurokit2 as nk
        from scipy.signal import resample_poly
        from fractions import Fraction
        analysis_fs = min(float(fs), 500.0)
        ratio = Fraction(analysis_fs / fs).limit_denominator(10000)
        samples = resample_poly(original, ratio.numerator, ratio.denominator) if fs > 500 else original.copy()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            clean = nk.ecg_clean(samples, sampling_rate=analysis_fs, method='neurokit')
            # Keep original signal polarity for display, but support inverted leads for detection.
            from scipy.signal import find_peaks, peak_widths
            def polarity_score(x):
                peaks, props = find_peaks(x, prominence=max(float(np.std(x)), 1e-12), distance=max(1,int(.25*analysis_fs)))
                if len(peaks) < 3: return 0
                widths = peak_widths(x, peaks)[0]
                return float(np.median(props['prominences'] / np.maximum(widths, 1)))
            inverted = polarity_score(-clean) > polarity_score(clean)
            detection = -clean if inverted else clean
            _, info = nk.ecg_peaks(detection, sampling_rate=analysis_fs, method='neurokit', correct_artifacts=False)
            rpeaks = np.asarray(info['ECG_R_Peaks'], dtype=int)
            if len(rpeaks) < 4:
                return unavailable('Wykryto zbyt mało kandydatów R do delineacji.', start, end)
            _, waves = nk.ecg_delineate(detection, rpeaks, sampling_rate=analysis_fs, method='dwt', check=False)
    except ImportError:
        return unavailable('Dekoder NeuroKit2 nie jest dostępny na serwerze.', start, end)
    except Exception:
        return unavailable('Algorytm nie ustalił stabilnej delineacji tego fragmentu. Oryginalny wykres jest dostępny.', start, end)

    # Similarity is a technical repeatability measure, NOT a clinical quality probability.
    before, after = int(.2*analysis_fs), int(.4*analysis_fs)
    segments = [clean[r-before:r+after] for r in rpeaks if r >= before and r+after < len(clean)]
    correlations = []
    if len(segments) >= 3:
        template = np.median(np.asarray(segments), axis=0)
        for segment in segments:
            if np.std(segment) > 0 and np.std(template) > 0:
                correlations.append(float(np.corrcoef(segment, template)[0, 1]))
    consistency = float(np.median(correlations)) if correlations else None
    if consistency is None or not np.isfinite(consistency) or consistency < .45:
        return unavailable('Niska powtarzalność wykrytych cykli. Może wynikać z zakłóceń lub zmienności morfologii; automatyczne pomiary wstrzymano.', start, end)

    def point(key, i):
        entries = waves.get(key, [])
        if i >= len(entries) or not np.isfinite(entries[i]):
            return None
        value = int(round(float(entries[i]) * fs / analysis_fs)) + start
        return value if start <= value < end else None

    beats, markers, intervals = [], [], []
    r_original = [int(round(int(r)*fs/analysis_fs))+start for r in rpeaks]
    for i, r in enumerate(r_original):
        # Edge cycles lack adequate P/T context; do not publish fabricated boundaries.
        if r-start < .35*fs or end-r < .6*fs:
            continue
        beat_id = f'beat-{r}'
        p = point('ECG_P_Peaks', i)
        q = point('ECG_Q_Peaks', i)
        s = point('ECG_S_Peaks', i)
        t = point('ECG_T_Peaks', i)
        pon, poff = point('ECG_P_Onsets', i), point('ECG_P_Offsets', i)
        qon, qoff = point('ECG_R_Onsets', i), point('ECG_R_Offsets', i)
        ton, toff = point('ECG_T_Onsets', i), point('ECG_T_Offsets', i)
        previous = r_original[i-1] if i else start
        following = r_original[i+1] if i+1 < len(r_original) else end
        if p is not None and not previous < p < r: p = None
        if q is not None and not previous < q < r: q = None
        if s is not None and not r < s < following: s = None
        if t is not None and not r < t < following: t = None
        if qon is None or qoff is None or not previous < qon < r < qoff < following:
            qon = qoff = None
        if p is None or pon is None or poff is None or not previous < pon < p < poff < (qon or r):
            pon = poff = None
        if t is None or ton is None or toff is None or not (qoff or r) < ton < t < toff < following:
            ton = toff = None
        measurements = {}
        def interval(name, a, b, lower, upper):
            if a is None or b is None or b <= a: return None
            ms = (b-a)/fs*1000
            # Broad validity checks prevent impossible boundaries, not clinical normal ranges.
            if not lower <= ms <= upper: return None
            intervals.append(dict(beatId=beat_id, label=name, startSample=a, endSample=b, durationMs=ms))
            return ms
        measurements['rrMs'] = (r-previous)/fs*1000 if i else None
        measurements['heartRateBpm'] = 60000/measurements['rrMs'] if measurements['rrMs'] else None
        measurements['pDurationMs'] = interval('P', pon, poff, 20, 300)
        measurements['prMs'] = interval('PR', pon, qon, 40, 500)
        measurements['qrsMs'] = interval('QRS', qon, qoff, 20, 300)
        measurements['qtMs'] = interval('QT', qon, toff, 100, 800)
        rr = measurements['rrMs']
        qt = measurements['qtMs']
        measurements['qtcFridericiaMs'] = qt / ((rr/1000)**(1/3)) if qt and rr and 300 <= rr <= 2000 else None
        for label, sample in (('P',p),('Q',q),('R',r),('S',s),('T',t)):
            if sample is not None:
                markers.append(dict(beatId=beat_id, label=label, sample=sample))
        beats.append(dict(id=beat_id, rSample=r, measurements=measurements))
    if not beats:
        return unavailable('Nie wyznaczono pełnych cykli poza brzegami fragmentu.', start, end)
    complete = sum(b['measurements']['prMs'] is not None and b['measurements']['qrsMs'] is not None and b['measurements']['qtMs'] is not None for b in beats)
    return dict(status='estimated', reason=None, startSample=start, endSample=end,
                method=METHOD, limitation=LIMITATION, markers=markers, intervals=intervals,
                beats=beats, summary=summarize_measurements(beats),
                quality=dict(templateCorrelation=consistency, completeBeats=complete, measuredBeats=len(beats),
                             completePercent=100*complete/len(beats), invertedForDetection=bool(inverted)),
                preprocessing='Filtr NeuroKit2 oraz resampling do maks. 500 Hz wyłącznie do detekcji. Znaczniki przeliczono na indeksy oryginału; amplitudy nie są pomiarami z przefiltrowanego sygnału.',
                exclusions=['Bez rozpoznania arytmii, niedokrwienia ani innych chorób.', 'Bez automatycznej oceny ST, osi serca i HRV.', 'QTc: Fridericia, z poprzedzającego RR; wymaga weryfikacji rytmu i granic QT.'])


def analyze_file(filename, content, sampling_rate=None, unit=None, channel_index=0, start_sample=0, sample_count=0):
    from app.ecg import decode_file, MAX_BYTES, MAX_VALUES, MAX_CHANNELS
    if not content or len(content) > MAX_BYTES:
        raise ValueError('Nieprawidłowy rozmiar pliku.')
    channels = decode_file(filename, content)
    if len(channels) > MAX_CHANNELS or sum(len(c['values']) for c in channels) > MAX_VALUES:
        raise ValueError('Przekroczono limit kanałów lub próbek.')
    if not 0 <= channel_index < len(channels):
        raise ValueError('Nieprawidłowy kanał.')
    if start_sample < 0 or sample_count < 0:
        raise ValueError('Nieprawidłowy zakres próbek.')
    c = channels[channel_index]
    fs = c['fs'] or sampling_rate
    result = analyze(c['values'], fs, start_sample, sample_count)
    return dict(result, channelIndex=channel_index, channelName=c['name'], sampleRate=fs,
                calibrationSource='file' if c['fs'] else 'user' if sampling_rate else 'unknown')


def analyze_uncalibrated(values, start=0, count=0):
    """Shape-only hypotheses. Internal normalization is NOT a recovered sample rate."""
    from app.ecg import detect_features, display_signal
    start = max(0, min(int(start), len(values)))
    end = min(len(values), start + max(5000, min(int(count), 20000)))
    if end-start < 5000:
        start = max(0, end-5000)
    raw = np.asarray(values[start:end], dtype=float)
    normalized, notes, _ = display_signal(raw)
    features = detect_features(normalized)
    spacing = features.get('medianSpacingSamples')
    if spacing is None or not 100 <= spacing <= 2000:
        return unavailable('Brak Hz i brak stabilnej skali kształtu: nie udało się oznaczyć kandydatów PQRST.', start, end)
    # One candidate cycle per virtual second only sets detector windows.
    # No physical time, HR or QTc can be inferred from this normalization.
    result = analyze(normalized, float(spacing), 0, len(raw))
    result['timeBasis'] = 'samples'
    result['reason'] = 'Tryb bez Hz: hipotezy PQRST na podstawie kształtu. Odległości w próbkach, bez pomiaru czasu i tętna.'
    result['method'] = 'Eksperymentalna normalizacja skali kształtu względem odstępu szczytów; ' + METHOD
    result['limitation'] = 'Skala detektora jest umowna, nie oznacza odzyskanego Hz. Granice mogą być błędne przy nietypowym rytmie lub morfologii. ' + LIMITATION
    result['preprocessing'] = 'Wewnętrzna normalizacja jednego cyklu do umownej sekundy wyłącznie do wyszukiwania kształtów; wyniki zwracane tylko w próbkach.'
    if notes: result['preprocessing'] += ' ' + ' '.join(notes)
    result['startSample'] += start
    result['endSample'] += start
    for marker in result['markers']:
        marker['sample'] += start
        marker['beatId'] = 'beat-' + str(int(marker['beatId'][5:])+start)
    for band in result['intervals']:
        band['startSample'] += start
        band['endSample'] += start
        band['beatId'] = 'beat-' + str(int(band['beatId'][5:])+start)
        band['durationSamples'] = band['endSample']-band['startSample']
        band['durationMs'] = None
    for beat in result['beats']:
        beat['rSample'] += start
        beat['id'] = 'beat-' + str(beat['rSample'])
        old = beat['measurements']
        beat['measurements'] = {key: None for key in old}
        for key in ('rrMs','pDurationMs','prMs','qrsMs','qtMs'):
            beat['measurements'][key.replace('Ms','Samples')] = round(old[key]*spacing/1000) if old.get(key) is not None else None
    result['summary'] = summarize_measurements(result['beats'])
    for key in ('rrSamples','pDurationSamples','prSamples','qrsSamples','qtSamples'):
        vals = [b['measurements'][key] for b in result['beats'] if b['measurements'].get(key) is not None]
        result['summary'][key] = dict(median=float(np.median(vals)),min=min(vals),max=max(vals),count=len(vals)) if vals else None
    return result
