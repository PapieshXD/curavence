"""Bounded, non-diagnostic waveform decoding. Never infer missing calibration."""
import csv
import base64
import io
import json
import math
import re
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

import numpy as np

MAX_BYTES = 25 * 1024 * 1024
MAX_VALUES = 2_000_000
MAX_CHANNELS = 64
MAX_PREVIEW_VALUES = MAX_VALUES


def number(value):
    try:
        result = float(str(value).strip().replace(",", "."))
        return result if math.isfinite(result) else np.nan
    except (ValueError, TypeError):
        return np.nan


def channel(name, values, fs=None, unit=None):
    values = np.asarray(values, dtype=float).reshape(-1)
    if values.size < 2 or values.size > MAX_VALUES:
        raise ValueError("Kanał musi zawierać od 2 do 2 000 000 próbek.")
    if fs is not None and (not math.isfinite(float(fs)) or not 0 < float(fs) <= 100000):
        raise ValueError("Nieprawidłowa częstotliwość próbkowania.")
    return dict(name=str(name)[:80], values=values, fs=float(fs) if fs else None, unit=str(unit)[:30] if unit else None)


def rate_from_duration(sample_count, duration, existing=None):
    duration = number(duration)
    if not np.isfinite(duration) or duration <= 0:
        raise ValueError('Czas rejestracji musi być dodatnią liczbą sekund.')
    rate = sample_count / duration
    if not 0 < rate <= 100000:
        raise ValueError('Nieprawidłowa częstotliwość wyliczona z czasu rejestracji.')
    if existing is not None and not np.isclose(float(existing), rate, rtol=.02):
        raise ValueError('Czas rejestracji jest sprzeczny z częstotliwością próbkowania.')
    return existing if existing is not None else rate


def table(text):
    metadata = '\n'.join(line for line in text.splitlines() if line.lstrip().startswith(('#', '%')))
    rate_match = re.search(r'(?:sampling[_ ]?rate|sample[_ ]?rate|sampling[_ ]?frequency|fs)\s*[:=]\s*(\d+(?:[.,]\d+)?)', metadata, re.I)
    unit_match = re.search(r'(?:unit|units|jednostka)\s*[:=]\s*(mV|uV|µV|V)\b', metadata)
    lines = [line for line in text.strip().splitlines() if not line.lstrip().startswith(('#', '%'))]
    if not lines:
        raise ValueError("Plik nie zawiera próbek.")
    first = lines[0]
    delimiter = '\t' if '\t' in first else ';' if ';' in first else ',' if ',' in first else None
    rows = list(csv.reader(lines, delimiter=delimiter)) if delimiter else [re.split(r'\s+', line.strip()) for line in lines]
    width = len(rows[0])
    if not 1 <= width <= MAX_CHANNELS + 1 or len(rows) * width > MAX_VALUES:
        raise ValueError("Tabela przekracza limit kanałów lub próbek.")
    header = rows.pop(0) if all(not np.isfinite(number(v)) and v.strip().lower() not in ('nan', 'inf', '-inf', 'null', '') for v in rows[0]) else [f"Kanał {i + 1}" for i in range(width)]
    rows = [row if row and any(v.strip() for v in row) else [''] * width for row in rows]
    if any(len(row) != width for row in rows):
        raise ValueError("Niespójna liczba kolumn. Użyj CSV, TSV lub kolumn rozdzielonych spacjami.")
    values = np.array([[number(v) for v in row] for row in rows])
    fs = number(rate_match.group(1)) if rate_match else None
    fs_source = 'header' if rate_match else 'unknown'
    time_column = next((i for i, label in enumerate(header) if re.fullmatch(r'(time|czas|t)(\s*[\[(]?(s|ms|sec)[\])]?)?', label.strip(), re.I)), None)
    if time_column is not None:
        times = values[:, time_column]
        delta = np.diff(times)
        if len(delta) == 0 or not np.all(np.isfinite(delta)) or np.any(delta <= 0):
            raise ValueError("Kolumna czasu zawiera nieprawidłowe lub nieuporządkowane wartości.")
        # An unqualified time column is not assumed to be seconds.
        if re.search(r'\b(s|ms|sec)\b', header[time_column], re.I):
            if not np.allclose(delta, np.median(delta), rtol=.02, atol=1e-8):
                raise ValueError("Nieregularne odstępy czasu. Przekształć zapis do równomiernego próbkowania.")
            time_fs = (1000 if re.search(r'\bms\b', header[time_column], re.I) else 1) / np.median(delta)
            if fs and not np.isclose(fs, time_fs, rtol=.02):
                raise ValueError("Częstotliwość z nagłówka jest sprzeczna z kolumną czasu.")
            fs = time_fs
            fs_source = 'time-column'
    duration_match = re.search(r'(?:durationSeconds|duration_s|recording_duration_s)\s*[:=]\s*([-+]?\d+(?:[.,]\d+)?)', metadata, re.I)
    if duration_match:
        had_rate = fs is not None
        fs = rate_from_duration(len(values), duration_match.group(1), fs)
        if not had_rate: fs_source = 'duration'
    result = []
    for i, label in enumerate(header):
        if i == time_column:
            continue
        match = re.search(r'[\[(](mV|uV|µV|V)[\])]', label)
        parsed = channel(label, values[:, i], fs, match.group(1) if match else unit_match.group(1) if unit_match else None)
        parsed['fsSource'] = fs_source
        result.append(parsed)
    return result


def structured(data):
    if isinstance(data, list):
        if data and isinstance(data[0], dict):
            keys = list(data[0])
            return table('\n'.join([','.join(keys)] + [','.join(str(row.get(k, '')) for k in keys) for row in data]))
        data = {"samples": data}
    if not isinstance(data, dict):
        raise ValueError("JSON musi zawierać samples lub channels.")
    fs = next((data[k] for k in ('samplingRate', 'sampleRate', 'fs', 'sampling_frequency', 'samplingFrequency', 'sample_rate') if k in data), None)
    unit = data.get('unit', data.get('units'))
    if fs is None and data.get('timeUnit') in ('s', 'ms') and isinstance(data.get('time'), list):
        times = np.asarray(data['time'], dtype=float)
        delta = np.diff(times)
        if len(delta) and np.all(np.isfinite(delta)) and np.all(delta > 0) and np.allclose(delta, np.median(delta), rtol=.02, atol=1e-8):
            fs = (1000 if data['timeUnit'] == 'ms' else 1) / np.median(delta)
    duration = data.get('durationSeconds', data.get('recordingDurationSeconds'))
    def calibrated_channel(name, values, rate, channel_unit):
        c = channel(name, values, rate, channel_unit)
        if duration is not None:
            c['fs'] = rate_from_duration(len(c['values']), duration, c['fs'])
            if rate is None: c['fsSource'] = 'duration'
        return c
    channels = data.get('channels', data.get('leads'))
    if isinstance(channels, dict):
        channels = [dict(name=k, samples=v) if isinstance(v, list) else dict(v, name=k) for k, v in channels.items()]
    if isinstance(channels, list):
        return [calibrated_channel(c.get('name', c.get('label', f'Kanał {i+1}')), c.get('samples', c.get('values', c.get('data'))), c.get('samplingRate', fs), c.get('unit', unit)) for i, c in enumerate(channels)]
    samples = data.get('samples', data.get('signal', data.get('data', data.get('val'))))
    if samples is None:
        raise ValueError("Brak tablicy samples, signal, data lub val.")
    matrix = np.asarray(samples, dtype=float)
    if matrix.ndim == 1:
        return [calibrated_channel('Kanał 1', matrix, fs, unit)]
    if matrix.ndim != 2:
        raise ValueError("Sygnał musi być wektorem lub macierzą kanałów.")
    if matrix.shape[0] <= 32 and matrix.shape[1] > 32:
        matrix = matrix.T
    names = data.get('channelNames', [])
    return [calibrated_channel(names[i] if i < len(names) else f'Kanał {i+1}', matrix[:, i], fs, unit) for i in range(matrix.shape[1])]


def xml_signal(content):
    from defusedxml import ElementTree
    root = ElementTree.fromstring(content)
    local = lambda node: node.tag.split('}')[-1].lower()
    fs_node = next((n for n in root.iter() if local(n) in ('samplingrate', 'samplerate', 'samplebase', 'samplingfrequency')), None)
    fs = number(fs_node.text) if fs_node is not None else None
    result = []
    for node in root.iter():
        if local(node) not in ('lead', 'channel', 'leaddata', 'sequence'):
            continue
        sample_node = next((n for n in node.iter() if local(n) in ('samples', 'values', 'digits', 'data')), None)
        if sample_node is None or not sample_node.text:
            continue
        raw = sample_node.text.strip()
        if not re.fullmatch(r'[\d\s,;.eE+\-]+', raw):
            continue
        name = node.get('name', node.get('label', node.get('id', f'Kanał {len(result)+1}')))
        values = [number(v) for v in re.split(r'[\s;,]+', raw) if v]
        result.append(channel(name, values, fs, node.get('unit')))
    if not result:
        raise ValueError("Ten XML nie zawiera jawnych próbek. Skompresowany eksport producenta wymaga konwersji do CSV, EDF lub DICOM Waveform.")
    return result


def decode_file(filename, content):
    suffix = Path(filename).suffix.lower()
    if suffix in ('.csv', '.tsv', '.txt'):
        text = content.decode('utf-16') if content.startswith((b'\xff\xfe', b'\xfe\xff')) else content.decode('utf-8-sig')
        return table(text)
    if suffix == '.json':
        return structured(json.loads(content))
    if suffix == '.xml':
        return xml_signal(content)
    if suffix == '.mat':
        from scipy.io import loadmat, whosmat
        if any(math.prod(shape) > MAX_VALUES for _, shape, _ in whosmat(io.BytesIO(content))):
            raise ValueError("Macierz MAT przekracza limit próbek.")
        data = loadmat(io.BytesIO(content), simplify_cells=True, variable_names=['val', 'data', 'signal', 'samples', 'fs', 'samplingRate', 'unit', 'channelNames'])
        return structured(data)
    if suffix in ('.dcm', '.dicom'):
        import pydicom
        ds = pydicom.dcmread(io.BytesIO(content))
        result = []
        for group_index, group in enumerate(getattr(ds, 'WaveformSequence', [])):
            if int(group.NumberOfWaveformSamples) * int(group.NumberOfWaveformChannels) > MAX_VALUES:
                raise ValueError("DICOM Waveform przekracza limit próbek.")
            values = ds.waveform_array(group_index)
            for i, definition in enumerate(group.ChannelDefinitionSequence):
                unit_seq = getattr(definition, 'ChannelSensitivityUnitsSequence', [])
                unit = getattr(unit_seq[0], 'CodeValue', None) if unit_seq else None
                label = getattr(definition, 'ChannelLabel', f'Grupa {group_index+1} / kanał {i+1}')
                result.append(channel(label, values[:, i], group.SamplingFrequency, unit))
        if not result:
            raise ValueError("Ten DICOM nie zawiera WaveformSequence z sygnałem EKG.")
        return result
    if suffix in ('.edf', '.bdf'):
        import pyedflib
        with tempfile.TemporaryDirectory(prefix='ecg-') as folder:
            path = Path(folder) / ('signal' + suffix)
            path.write_bytes(content)
            with pyedflib.EdfReader(str(path)) as reader:
                if reader.signals_in_file > MAX_CHANNELS or sum(reader.getNSamples()) > MAX_VALUES:
                    raise ValueError("EDF/BDF przekracza limit próbek lub kanałów.")
                return [channel(reader.getLabel(i), reader.readSignal(i), reader.getSampleFrequency(i), reader.getPhysicalDimension(i)) for i in range(reader.signals_in_file)]
    if suffix == '.zip':
        return archive(content)
    if suffix in ('.hea', '.dat', '.atr'):
        raise ValueError("Zapis WFDB wymaga nagłówka HEA i powiązanych plików DAT w jednej paczce ZIP. Sam ATR zawiera adnotacje, nie sygnał.")
    raise ValueError("Ten wariant formatu nie ma dekodera sygnału. Wyeksportuj próbki do CSV/JSON, EDF/BDF, MAT lub DICOM Waveform. Oryginał pozostaje dostępny do pobrania.")


def archive(content):
    with zipfile.ZipFile(io.BytesIO(content)) as source, tempfile.TemporaryDirectory(prefix='ecg-') as folder:
        entries = source.infolist()
        if len(entries) > 64 or sum(e.file_size for e in entries) > MAX_BYTES:
            raise ValueError("ZIP przekracza limit 64 plików lub 25 MB po rozpakowaniu.")
        files = {}
        for entry in entries:
            path = PurePosixPath(entry.filename.replace('\\', '/'))
            if path.is_absolute() or '..' in path.parts or ':' in str(path) or ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError("ZIP zawiera niedozwoloną ścieżkę.")
            if entry.is_dir():
                continue
            target = Path(folder).joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                raise ValueError("ZIP zawiera powtórzone nazwy plików.")
            target.write_bytes(source.read(entry))
            files[str(path)] = target
        headers = sorted((p for p in files.values() if p.suffix.lower() == '.hea'), key=lambda p: [int(part) if part.isdigit() else part.lower() for part in re.split(r'(\d+)', str(p))])
        result = []
        if headers:
            import wfdb
            for header in headers:
                lines = [line for line in header.read_text().splitlines() if line and not line.startswith('#')]
                if not lines or '/' in lines[0].split()[0]:
                    raise ValueError("Wielosegmentowy WFDB wymaga eksportu do pojedynczego rekordu.")
                # Prevent local file references escaping the extracted archive, before wfdb reads anything.
                for line in lines[1:]:
                    ref = line.split()[0]
                    if '/' in ref or '\\' in ref or ':' in ref or ref in ('.', '..', '~'):
                        raise ValueError("Niedozwolone odwołanie do pliku w nagłówku WFDB.")
                    if not (header.parent / ref).is_file():
                        raise ValueError("W ZIP brakuje pliku wskazanego w nagłówku HEA.")
                info = wfdb.rdheader(str(header.with_suffix('')))
                if info.n_sig > MAX_CHANNELS or info.sig_len * info.n_sig > MAX_VALUES:
                    raise ValueError("WFDB przekracza limit próbek lub kanałów.")
                if len(result) + info.n_sig > MAX_CHANNELS or sum(len(c['values']) for c in result) + info.sig_len * info.n_sig > MAX_VALUES:
                    raise ValueError('WFDB ZIP przekracza łączny limit kanałów lub próbek.')
                record = wfdb.rdrecord(str(header.with_suffix('')), return_res=32)
                result.extend(channel(f'{header.stem} / {name}', record.p_signal[:, i], record.fs, record.units[i]) for i, name in enumerate(record.sig_name))
        else:
            for name, path in files.items():
                if path.suffix.lower() in ('.csv', '.tsv', '.txt', '.json', '.xml', '.mat', '.edf', '.bdf', '.dcm', '.dicom'):
                    decoded = decode_file(name, path.read_bytes())
                    for c in decoded:
                        c['name'] = f'{path.stem} / {c["name"]}'[:80]
                    result.extend(decoded)
        if not result:
            raise ValueError("ZIP nie zawiera obsługiwanego zapisu sygnału.")
        return result


def display_signal(values, fs=None):
    """Conservative narrow-band suppression for display only; never diagnostic calibration."""
    from scipy.signal import welch, iirnotch, filtfilt, find_peaks
    output = values.copy()
    notes = []
    finite = np.isfinite(values)
    # Never bridge missing samples or filter a short segment.
    if len(values) >= 2048 and finite.all() and np.ptp(values) > 0:
        frequencies, power = welch(values, fs=1, nperseg=min(8192, len(values)))
        candidates = np.where((frequencies > .04) & (frequencies < .45))[0]
        peak = candidates[np.argmax(power[candidates])]
        local = power[max(0, peak-20):peak+21]
        share = power[max(0, peak-2):peak+3].sum() / max(power.sum(), 1e-30)
        prominence = power[peak] / max(float(np.median(local)), 1e-30)
        frequency = float(frequencies[peak])
        persistent = all(abs(float(frequencies[candidates[np.argmax(welch(part, fs=1, nperseg=min(8192, len(values)))[1][candidates])]]) - frequency) < .001
                         for part in [values[:min(8192, len(values))], values[-min(8192, len(values)):]])
        mains_compatible = fs is None or min(abs(frequency * fs - 50), abs(frequency * fs - 60)) < 1
        if prominence > 100 and share > .12 and persistent and mains_compatible:
            b, a = iirnotch(frequency, 35, fs=1)
            output = filtfilt(b, a, values)
            notes.append(f"Automatycznie stłumiono dominującą wąskopasmową składową {frequency:.6f} cyklu/próbkę (notch Q=35, filtracja dwukierunkowa).")
    window = int(fs * 6) if fs else 1500
    # Estimate spacing only for framing; do not label peaks as QRS or calculate heart rate.
    if np.isfinite(output).all() and len(output) > 100:
        peaks, _ = find_peaks(output, prominence=max(float(np.std(output))*1.5, 1e-12), distance=max(10, int(fs*.25)) if fs else 30)
        if len(peaks) >= 5:
            spacing = float(np.median(np.diff(peaks)))
            if np.median(np.abs(np.diff(peaks)-spacing)) < spacing*.3:
                window = int(spacing*5)
    return output, notes, max(100, min(window, 10000, len(values)))


def detect_features(values, fs=None):
    """Unvalidated candidate peaks, never a diagnosis or a replacement for ECG delineation."""
    from scipy.signal import find_peaks
    result = dict(status='unavailable', peakCount=0, peakIndices=[], medianSpacingSamples=None,
                  medianSpacingMs=None, peakRatePerMinute=None, spacingVariationPercent=None,
                  method='scipy.find_peaks: prominence=1.5*SD, distance=0.25s or 30 samples; dominant polarity',
                  limitation='Szczyty są kandydatami, nie potwierdzonymi załamkami R. Częstość szczytów nie jest zweryfikowanym tętnem; zmienność odstępów nie jest HRV.')
    if len(values) < 100 or not np.isfinite(values).all() or np.ptp(values) == 0:
        return result
    distance = max(1, int(fs*.25)) if fs else 30
    prominence = max(float(np.std(values))*1.5, 1e-12)
    positive, pp = find_peaks(values, prominence=prominence, distance=distance)
    negative, pn = find_peaks(-values, prominence=prominence, distance=distance)
    score = lambda props: np.median(props['prominences']) if len(props['prominences']) else 0
    peaks = positive if score(pp) >= score(pn) else negative
    if len(peaks) < 3:
        return result
    spacing = np.diff(peaks)
    median = float(np.median(spacing))
    result.update(status='estimated', peakCount=len(peaks), peakIndices=peaks.tolist(),
                  medianSpacingSamples=median, medianSpacingMs=median/fs*1000 if fs else None,
                  peakRatePerMinute=60*fs/median if fs else None,
                  spacingVariationPercent=float(np.std(spacing)/np.mean(spacing)*100))
    return result


def preview(filename, content, sampling_rate=None, unit=None):
    if not content or len(content) > MAX_BYTES:
        raise ValueError("Plik jest pusty lub przekracza 25 MB.")
    if Path(filename).suffix.lower() in ('.png', '.jpg', '.jpeg', '.gif', '.bmp', '.tif', '.tiff', '.webp'):
        from PIL import Image
        with Image.open(io.BytesIO(content)) as image:
            if image.width * image.height > 40_000_000:
                raise ValueError("Obraz EKG przekracza limit 40 milionów pikseli.")
            frames = getattr(image, 'n_frames', 1)
            image.thumbnail((2000, 2000))
            output = io.BytesIO()
            image.convert('RGB').save(output, format='PNG')
        encoded = base64.b64encode(output.getvalue()).decode('ascii')
        warnings = ["To obraz zapisu. Nie odtworzono cyfrowych próbek ani kalibracji. AI może opisać widoczną treść i czytelność, bez pomiarów sygnału."]
        if frames > 1:
            warnings.append(f"Plik zawiera {frames} klatek/stron. Podgląd i AI obejmują tylko pierwszą; pełny plik jest dostępny do pobrania.")
        return dict(format=Path(filename).suffix[1:].upper(), channels=[], imageDataUrl='data:image/png;base64,' + encoded,
                    warnings=warnings, evidence=dict(kind='image', frames=frames, previewedFrame=1, warnings=warnings))
    channels = decode_file(filename, content)
    if not channels or len(channels) > MAX_CHANNELS or sum(c['values'].size for c in channels) > MAX_VALUES:
        raise ValueError("Zapis przekracza limit 64 kanałów lub 2 000 000 próbek.")
    warnings = []
    if sampling_rate is not None and (not math.isfinite(sampling_rate) or not 0 < sampling_rate <= 100000):
        raise ValueError("Częstotliwość musi mieścić się w zakresie 0–100000 Hz.")
    if unit not in (None, 'mV', 'uV', 'V'):
        raise ValueError("Nieobsługiwana jednostka.")
    limit = MAX_PREVIEW_VALUES
    result = []
    for c in channels:
        fs = c['fs'] or sampling_rate
        units = c['unit'] or unit
        values = c['values']
        finite = values[np.isfinite(values)]
        if finite.size < 2:
            raise ValueError(f"{c['name']}: brak wystarczających próbek liczbowych.")
        shown = values[:limit]
        displayed, processing, automatic_window = display_signal(shown, fs)
        features = detect_features(displayed, fs)
        stats = dict(min=float(finite.min()), max=float(finite.max()), mean=float(finite.mean()),
                     standardDeviation=float(finite.std()), missingSamples=int(values.size - finite.size),
                     constant=bool(np.ptp(finite) == 0), median=float(np.median(finite)),
                     rms=float(np.sqrt(np.mean(finite ** 2))), peakToPeak=float(np.ptp(finite)),
                     validPercent=float(100 * finite.size / values.size))
        result.append(dict(name=c['name'], sampleRate=fs, unit=units, calibrationSource='file' if c['fs'] else 'user' if sampling_rate else 'unknown',
                           sampleCount=int(values.size), displayedSamples=int(shown.size), durationSeconds=values.size/fs if fs else None,
                           samples=[float(v) if np.isfinite(v) else None for v in shown],
                           displaySamples=[float(v) if np.isfinite(v) else None for v in displayed],
                           displayProcessing=processing, automaticWindow=automatic_window, statistics=stats,
                           features=features, parameterSources=dict(
                               samplingRate=c.get('fsSource', 'metadata') if c['fs'] else 'user' if sampling_rate else 'unknown',
                               unit='metadata' if c['unit'] else 'user' if unit else 'unknown')))
    if any(c['sampleRate'] is None for c in result):
        warnings.append("Brak częstotliwości próbkowania: oś X pokazuje numer próbki. Nie można wyliczyć czasu ani tętna.")
    if any(c['unit'] is None for c in result):
        warnings.append("Brak jednostki amplitudy: pokazujemy wartości źródłowe, bez przeliczania na mV.")
    if any(c['sampleCount'] > c['displayedSamples'] for c in result):
        warnings.append("Podgląd obejmuje początek zapisu (limit 240 000 próbek łącznie). Statystyki obejmują cały odczytany zapis.")
    if any(c['statistics']['missingSamples'] for c in result):
        warnings.append("Zapis zawiera brakujące lub nieliczbowe próbki. Wykres pokazuje przerwy.")
    evidence_channels = [{k: v for k, v in c.items() if k not in ('samples', 'displaySamples', 'features')} | {'features': {k: v for k, v in c['features'].items() if k != 'peakIndices'}} for c in result]
    evidence = dict(format=Path(filename).suffix.lower(), channels=evidence_channels, warnings=warnings,
                    scope="Statystyki techniczne sygnału. Nie wyznaczono załamków P/QRS/T, odstępów ani rozpoznania rytmu. Nie potwierdzono prawidłowości klinicznej.")
    return dict(format=Path(filename).suffix[1:].upper(), channels=result, warnings=warnings, evidence=evidence)
