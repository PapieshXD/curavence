import { Subscription } from 'rxjs';
import { Api } from '../../../core/services/api';
import { WorkspaceStore } from '../../../core/services/workspace-store';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { EcgCalibration, EcgChannel, EcgPreview, EcgSelection, EcgMorphology } from '../../../core/models/ecg';

/** Min/max rendering preserves short spikes when a window has more samples than pixels. */
export function waveformPath(values: (number | null)[], min: number, max: number, height = 136): string {
  if (values.length < 2) return '';
  const stride = Math.max(1, Math.ceil(values.length / 900));
  const points: { index: number; value: number | null }[] = [];
  for (let start = 0; start < values.length; start += stride) {
    const end = Math.min(values.length, start + stride);
    let low = start, high = start;
    if (values.slice(start, end).some(value => value == null)) {
      // Preserve gaps instead of connecting across missing data.
      for (let i = start; i < end; i++) points.push({ index: i, value: values[i] });
      continue;
    }
    for (let i = start; i < end; i++) {
      if (values[i]! < values[low]!) low = i;
      if (values[i]! > values[high]!) high = i;
    }
    for (const index of [...new Set([start, low, high, end - 1])].sort((a, b) => a - b)) points.push({ index, value: values[index] });
  }
  let pen = false;
  return points.map(({ index, value }) => {
    if (value == null) { pen = false; return ''; }
    const x = 64 + index / (values.length - 1) * 900;
    const y = 18 + (max - value) / (max - min || 1) * height;
    const command = pen ? 'L' : 'M'; pen = true;
    return `${command}${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(' ');
}

export function sampleStatistics(values: (number | null)[]) {
  let count = 0, mean = 0, m2 = 0, squares = 0, min = Infinity, max = -Infinity;
  for (const value of values) {
    if (value == null || !Number.isFinite(value)) continue;
    count++;
    const delta = value - mean; mean += delta / count; m2 += delta * (value - mean);
    squares += value * value; min = Math.min(min, value); max = Math.max(max, value);
  }
  return { count, missing:values.length - count, mean:count ? mean : null,
    min:count ? min : null, max:count ? max : null, rms:count ? Math.sqrt(squares / count) : null,
    standardDeviation:count ? Math.sqrt(m2 / count) : null, peakToPeak:count ? max - min : null };
}

@Component({
  selector: 'app-ecg-waveform',
  imports: [FormsModule, DecimalPipe],
  template: `
    <section class="ecg-viewer" aria-label="Wykresy sygnału EKG">
      <header class="signal-heading"><div><span class="eyebrow">ZAPIS SYGNAŁU · {{ data().format }}</span><h3>Przebieg EKG</h3><p>{{ raw() ? 'Oryginalne próbki · bez filtracji' : 'Automatyczne dopasowanie fragmentu i amplitudy' }}</p></div><span class="channel-badge">{{ data().channels.length }} kanał(y)</span></header>
      <details class="record-details"><summary>Parametry pliku i kalibracja</summary><div class="signal-metrics">
        <div><span>Próbki / kanał</span><strong>{{ first().sampleCount | number }}</strong></div>
        <div><span>Próbkowanie</span><strong>{{ first().sampleRate ? (first().sampleRate | number:'1.0-2') + ' Hz' : 'Nie podano' }}</strong></div>
        <div><span>Czas zapisu</span><strong>{{ first().durationSeconds ? (first().durationSeconds | number:'1.1-2') + ' s' : 'Nieznany' }}</strong></div>
        <div><span>Amplituda</span><strong>{{ first().unit || 'Jednostka nieznana' }}</strong></div>
      </div>
      @if (data().channels.length > 1) { <p class="metric-note">Parametry powyżej dotyczą pierwszego kanału. Każdy wykres ma własną oś i kalibrację.</p> }
      @if (data().warnings.length) { <div class="signal-notice">@for (warning of data().warnings; track warning) { <p>{{ warning }}</p> }</div> }
      @if (needsCalibration()) {
        <details class="calibration"><summary>Uzupełnij kalibrację z dokumentacji urządzenia</summary>
          <p>Parametry zostaną użyte w tym podglądzie i rozmowie z AI. Nie zmieniają pliku.</p>
          <div><label>Częstotliwość (Hz)<input type="number" min="0.001" max="100000" step="any" placeholder="np. 500" [(ngModel)]="samplingRate" /></label>
          <label>Jednostka<select [(ngModel)]="unit"><option value="">Nieznana</option><option value="mV">mV</option><option value="uV">µV</option><option value="V">V</option></select></label>
          <button type="button" (click)="applyCalibration()" [disabled]="samplingRate !== null && !(samplingRate > 0 && samplingRate <= 100000)">Zastosuj</button></div>
        </details>
      }
      </details>
      @if (!first().sampleRate) {
        <section class="morphology-panel" aria-label="Przeliczanie na czas">
          <h4>Oś czasu zamiast numerów próbek</h4>
          <p>Podaj znany czas całej rejestracji z urządzenia lub eksportu. Częstotliwość obliczymy jako liczbę próbek / czas. Nie podawaj oszacowania na podstawie wyglądu EKG.</p>
          <label>Czas całej rejestracji (sekundy)<input type="number" min="0.001" step="any" [(ngModel)]="recordingSeconds" /></label>
          <button type="button" (click)="applyDuration()" [disabled]="!durationRate()">Przelicz wykres na sekundy</button>
          @if (durationRate(); as rate) { <p>Wyliczona częstotliwość: {{ rate | number:'1.0-3' }} Hz · kalibracja z podanego czasu.</p> }
        </section>
      }
      <div class="auto-toolbar"><strong>{{ raw() ? 'Sygnał oryginalny' : 'Widok automatyczny' }}</strong><button type="button" (click)="raw.set(!raw())">{{ raw() ? 'Wróć do widoku automatycznego' : 'Pokaż oryginał' }}</button></div>
      @if (!raw() && filtered()) { <p class="filter-note">Podgląd po automatycznym tłumieniu zakłócenia wąskopasmowego. Do oceny morfologii porównaj z oryginałem. Brak kalibracji nie jest uzupełniany przez filtr.</p> }
      <section class="navigator" aria-label="Nawigacja po całym zapisie">
        <div class="navigator-heading"><strong>Cały zapis</strong><span>Przeciągnij wykres · kółko: przybliżenie · Shift + kółko: przesuwanie</span></div>
        <svg class="overview" viewBox="0 0 1000 100" preserveAspectRatio="none" role="slider" tabindex="0" aria-label="Pozycja w całym zapisie" aria-valuemin="0" [attr.aria-valuemax]="maxStart()" [attr.aria-valuenow]="position()" (pointerdown)="overviewSeek($event)" (keydown)="navigateKey($event)">
          <path [attr.d]="overviewPath()" fill="none" stroke="#83a7aa" stroke-width="1" />
          <rect [attr.x]="64 + position() / maxLength() * 900" y="5" [attr.width]="Math.max(3, visibleCount() / maxLength() * 900)" height="90" fill="#168b9026" stroke="#168b90" stroke-width="2" />
        </svg>
        <div class="navigation-actions"><button type="button" (click)="position.set(0)" [disabled]="position() === 0">⏮ Początek</button><button type="button" (click)="move(-1)" [disabled]="position() === 0">← Wstecz</button><strong>Próbki {{ position() | number:'1.0-0' }} – {{ Math.min(maxLength()-1,position()+visibleCount()-1) | number:'1.0-0' }} / {{ maxLength() | number }}</strong><button type="button" (click)="move(1)" [disabled]="position() >= maxStart()">Dalej →</button><button type="button" (click)="position.set(maxStart())" [disabled]="position() >= maxStart()">Koniec ⏭</button></div>
        <input aria-label="Przesuń po całym zapisie" type="range" min="0" [max]="maxStart()" [ngModel]="position()" (ngModelChange)="position.set(+$event)" />
      </section>
      <details class="manual-controls"><summary>Przewijanie i ręczne powiększenie</summary>
      <div class="chart-controls">
        <label>Odprowadzenie<select [ngModel]="selected()" (ngModelChange)="selected.set($event)"><option value="all">Wszystkie kanały</option>@for (c of data().channels; track $index) { <option [value]="$index.toString()">{{ c.name }}</option> }</select></label>
        <label>Okno<select [ngModel]="windowSize()" (ngModelChange)="setWindow(+$event)"><option [value]="automaticWindow()">Automatycznie ({{ automaticWindow() }} próbek)</option><option [value]="100">100 próbek</option><option [value]="250">250 próbek</option><option [value]="500">500 próbek</option><option [value]="2500">2500 próbek</option><option [value]="10000">10 000 próbek</option><option [value]="0">Cały podgląd</option></select></label>
        <div class="zoom"><button type="button" (click)="zoom(1)" [disabled]="windowSize() === 100">＋ Przybliż</button><button type="button" (click)="zoom(-1)" [disabled]="windowSize() === 0">− Oddal</button></div><div class="paging"><button type="button" aria-label="Poprzedni fragment" [disabled]="position() === 0" (click)="move(-1)">←</button><button type="button" aria-label="Następny fragment" [disabled]="position() >= maxStart()" (click)="move(1)">→</button></div>
      </div>
      <div class="position"><label for="ecg-position">Początek okna: próbka {{ position() | number }}</label><input id="ecg-position" type="range" min="0" [max]="maxStart()" [ngModel]="position()" (ngModelChange)="position.set(+$event)" [disabled]="!maxStart()" /></div>
      </details>
      @if (data().channels.some(missingRate)) {
        <section class="morphology-panel" aria-label="AI — brakująca kalibracja">
          <h4>AI · ustalanie brakującego Hz</h4>
          @if (calibrationAiLoading()) { <p role="status">AI sprawdza dostępne metadane i przesłanki… Wykres pozostaje dostępny.</p> }
          @if (calibrationAiText()) { <p style="white-space:pre-wrap">{{ calibrationAiText() }}</p> }
          <small>Ocena AI nie zmienia automatycznie skali wykresu. Hipoteza nie jest potwierdzoną kalibracją.</small>
        </section>
      }
      <section class="morphology-panel" aria-label="Analiza PQRST">
        <div class="morphology-heading"><div><span class="eyebrow">POMIARY ALGORYTMICZNE</span><h4>P–QRS–T na wykresie</h4></div>
          <label>Kanał analizy<select [ngModel]="analysisChannel()" (ngModelChange)="analysisChannel.set(+$event); selected.set($event.toString())">@for (c of data().channels; track $index) { <option [value]="$index">{{ c.name }}</option> }</select></label>
          <label class="annotation-toggle"><input type="checkbox" [ngModel]="showAnnotations()" (ngModelChange)="showAnnotations.set($event)" /> Oznaczenia</label>
        </div>
        <p>Analiza podąża za początkiem okna. Przy znanym Hz obejmuje 10–30 sekund; bez Hz analizuje kształt w oknie próbek. Wyniki są szacunkowe, do weryfikacji przez diagnostę.</p>
        @if (morphologyLoading()) { <p role="status">Wyznaczanie załamków i granic pomiarów…</p> }
        @if (morphologyError()) { <p class="filter-note" role="alert">{{ morphologyError() }}</p><button type="button" (click)="analysisRevision.update(increment)">Ponów analizę</button> }
        @if (morphology(); as m) {
          @if (m.status === 'unavailable') { <p class="filter-note">{{ m.reason }}</p> }
          @else {
            @if (m.timeBasis === 'samples') { <p class="filter-note">{{ m.reason }}</p> }
            <div class="morphology-scope"><strong>{{ m.channelName }} · próbki {{ m.startSample | number }} – {{ m.endSample - 1 | number }}</strong><span>Pełne pomiary PR/QRS/QT: {{ m.quality.completeBeats }} / {{ m.quality.measuredBeats }} cykli · podobieństwo cykli: {{ m.quality.templateCorrelation | number:'1.2-2' }} (nie pewność diagnozy)</span></div>
            <div class="morphology-metrics">@for (metric of morphologyMetrics(); track metric.key) { <div><span>{{ metric.label }}</span><strong>{{ metric.value == null ? '—' : (metric.value | number:'1.0-1') }} <small>{{ metric.unit }}</small></strong><small>{{ metric.count }} pomiarów · mediana</small>@if (metric.min != null) { <small>Zakres {{ metric.min | number:'1.0-1' }}–{{ metric.max | number:'1.0-1' }} {{ metric.unit }}</small> }</div> }</div>
            <div class="morphology-heading"><label>Cykl do sprawdzenia<select [ngModel]="selectedBeat()" (ngModelChange)="focusBeat($event)">@for (beat of m.beats; track beat.id) { <option [value]="beat.id">R · próbka {{ beat.rSample }}</option> }</select></label><div class="wave-legend">@for (wave of ['P','Q','R','S','T']; track wave) { <span [style.color]="waveColor(wave)">● {{ wave }}</span> }</div></div>
            <p>Kliknij oznaczenie na sygnale, aby wybrać cykl. Paski PR, QRS i QT pokazują granice pomiaru wybranego cyklu; najedź, aby odczytać odległość.</p>
            @if (m.timeBasis !== 'samples' && selectedMeasurements(); as selected) { <p class="selected-measurements">Wybrany cykl · PR: {{ selected['prMs'] == null ? '—' : (selected['prMs'] | number:'1.0-1') + ' ms' }} · QRS: {{ selected['qrsMs'] == null ? '—' : (selected['qrsMs'] | number:'1.0-1') + ' ms' }} · QT: {{ selected['qtMs'] == null ? '—' : (selected['qtMs'] | number:'1.0-1') + ' ms' }} · QTcF: {{ selected['qtcFridericiaMs'] == null ? '—' : (selected['qtcFridericiaMs'] | number:'1.0-1') + ' ms' }}</p> }
            <details><summary>Metoda i ograniczenia</summary><p>{{ m.method }}</p><p>{{ m.preprocessing }}</p><p>{{ m.limitation }}</p>@for (limitation of m.exclusions ?? []; track limitation) { <p>{{ limitation }}</p> }</details>
          }
        }
      </section>
      <div class="lead-list">
        @for (chart of charts(); track chart.index) {
          <article class="lead-card"><div class="lead-header"><strong><i></i>{{ chart.channel.name }}</strong><span>{{ chart.channel.unit || 'wartości źródłowe' }} · {{ chart.channel.sampleRate ? (chart.channel.sampleRate | number:'1.0-2') + ' Hz' : 'brak Hz' }}</span></div>
            @if (!chart.hasSamples) { <p class="flat-warning">Brak próbek tego kanału w wybranym oknie.</p> }
            @if (chart.channel.statistics.constant) { <p class="flat-warning">Kanał stały — brak zmienności próbek.</p> }
            <div class="signal-plot" tabindex="0" aria-label="Przeciągnij sygnał; strzałki przesuwają, plus i minus zmieniają powiększenie" (pointerdown)="panStart($event)" (pointermove)="panMove($event)" (pointerup)="panEnd($event)" (pointercancel)="panEnd($event)" (wheel)="wheel($event)" (keydown)="navigateKey($event)"><svg viewBox="0 0 1000 370" role="img" [attr.aria-label]="'Sygnał ' + chart.channel.name + ', próbki ' + position() + ' do ' + chart.end">
              <defs><pattern [id]="'ecg-grid-' + chart.index" x="64" y="18" width="36" height="30" patternUnits="userSpaceOnUse"><path d="M 36 0 L 0 0 0 30" fill="none" stroke="#f3dede" stroke-width="1" /></pattern></defs>
              <rect x="64" y="18" width="900" height="300" [attr.fill]="'url(#ecg-grid-' + chart.index + ')'" />
              @for (tick of chart.yTicks; track $index) { <text x="55" [attr.y]="tick.y + 4" text-anchor="end">{{ tick.value | number:'1.0-3' }}</text><line x1="64" x2="964" [attr.y1]="tick.y" [attr.y2]="tick.y" stroke="#e4bcbc" /> }
              <path [attr.d]="chart.path" fill="none" stroke="#143943" stroke-width="1.2" vector-effect="non-scaling-stroke" />
              @for (band of chart.bands; track band.label) { <g><title>{{ band.label }}: {{ (band.durationSamples ?? band.durationMs) | number:'1.0-1' }} {{ band.durationSamples != null ? 'próbek' : 'ms' }} · próbki {{ band.startSample }}–{{ band.endSample }}</title><rect [attr.x]="band.x" [attr.y]="band.y" [attr.width]="band.width" height="18" [attr.fill]="waveColor(band.label)" opacity="0.18" /><text [attr.x]="band.x + band.width/2" [attr.y]="band.y + 14" text-anchor="middle" class="interval-label">{{ band.label }}</text></g> }
              @for (marker of chart.markers; track marker.label + marker.sample) { <g role="button" tabindex="0" [attr.aria-label]="marker.label + ', próbka ' + marker.sample" (pointerdown)="$event.stopPropagation()" (click)="selectedBeat.set(marker.beatId)" (keydown.enter)="selectedBeat.set(marker.beatId)"><title>{{ marker.label }} · próbka {{ marker.sample }} · automatyczne oznaczenie</title><circle [attr.cx]="marker.x" [attr.cy]="marker.y" [attr.r]="marker.beatId === selectedBeat() ? 5 : 3.5" [attr.fill]="waveColor(marker.label)" stroke="white" stroke-width="1" /><text [attr.x]="marker.x" [attr.y]="Math.max(17,marker.y - (marker.label === 'Q' || marker.label === 'S' ? -24 : 12))" text-anchor="middle" [attr.fill]="waveColor(marker.label)" class="marker-label">{{ marker.label }}</text></g> }
              @for (tick of chart.xTicks; track $index) { <text [attr.x]="tick.x" y="350" text-anchor="middle">{{ tick.value | number:(chart.channel.sampleRate ? '1.0-2' : '1.0-0') }}{{ chart.channel.sampleRate ? ' s' : '' }}</text> }
            </svg></div>
            <footer><span>Zakres okna: {{ chart.min | number:'1.0-3' }} – {{ chart.max | number:'1.0-3' }} {{ chart.channel.unit || 'j. źródłowe' }}</span><span>{{ chart.channel.statistics.missingSamples | number }} brakujących próbek w zapisie</span></footer>
            <section class="detected"><h4>Parametry rozpoznane automatycznie</h4>
              <p>Hz: {{ sourceLabel(chart.channel.parameterSources?.samplingRate) }} · jednostka: {{ sourceLabel(chart.channel.parameterSources?.unit) }}.</p>
              @if (chart.channel.features; as features) {
                @if (features.status === 'estimated') {
                  <div class="feature-grid"><div><span>Kandydaci na szczyty</span><strong>{{ features.peakCount | number }}</strong></div><div><span>Mediana odstępu</span><strong>{{ features.medianSpacingSamples | number:'1.0-1' }} próbek</strong></div><div><span>Odstęp w czasie</span><strong>{{ features.medianSpacingMs == null ? 'Brak Hz' : (features.medianSpacingMs | number:'1.1-1') + ' ms' }}</strong></div><div><span>Częstość szczytów / min</span><strong>{{ features.peakRatePerMinute == null ? 'Brak Hz' : (features.peakRatePerMinute | number:'1.1-1') }}</strong></div><div><span>Zmienność odstępów (CV)</span><strong>{{ features.spacingVariationPercent | number:'1.1-1' }}%</strong></div></div>
                  <p>{{ features.limitation }}</p>
                } @else { <p>Za mało wiarygodnych danych do oszacowania odstępów. Nie wyznaczono szczytów.</p> }
              }
            </section>
            <section class="statistics"><h4>Statystyki z oryginalnych próbek</h4><p>Widoczny fragment: {{ chart.windowStats.count | number }} prawidłowych próbek, {{ chart.windowStats.missing | number }} brakujących. Filtr podglądu nie zmienia pomiarów.</p>
              <div class="stats-scroll"><table><thead><tr><th>Parametr</th><th>Widoczny fragment</th><th>Cały zapis</th></tr></thead><tbody>
                @for (metric of chart.metrics; track metric.label) { <tr><th>{{ metric.label }}</th><td>{{ metric.window == null ? '—' : (metric.window | number:'1.0-4') }}</td><td>{{ metric.total == null ? '—' : (metric.total | number:'1.0-4') }}</td></tr> }
              </tbody></table></div><p>Jednostka amplitudy: {{ chart.channel.unit || 'nieznana (wartości źródłowe)' }}. SD i RMS dotyczą amplitudy, nie HRV. {{ chart.channel.sampleRate ? 'Czas fragmentu: ' + (chart.sampleLength / chart.channel.sampleRate | number:'1.2-2') + ' s.' : 'Czas, tętno i odstępy RR: brak Hz — nie wyliczamy.' }}</p>
            </section>
          </article>
        }
      </div>
      <p class="chart-footnote">Osie amplitudy są dopasowane osobno do każdego okna. Siatka pomocnicza nie oznacza standardowej prędkości papieru EKG. Podgląd nie stanowi oceny klinicznej.</p>
    </section>
  `,
  styles: [`
    :host { display:block; width:100%; min-width:0; } .ecg-viewer { padding:22px; color:var(--ink); background:#f8fbfb; }
    .signal-heading { display:flex; justify-content:space-between; align-items:center; gap:14px; } .eyebrow { font-size:10px; letter-spacing:.1em; color:#4d8081; font-weight:700; } h3 { margin:7px 0 5px; font-size:23px; } p { font-size:12px; line-height:1.6; color:#567078; margin:4px 0; }
    .channel-badge { background:#e1f1ee; color:#12696c; border-radius:20px; padding:8px 12px; font-size:12px; white-space:nowrap; }
    .signal-metrics { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); border:1px solid #dae6e5; background:white; border-radius:10px; margin:20px 0 14px; } .signal-metrics div { padding:14px 12px; border-right:1px solid #e7eeed; } .signal-metrics div:last-child { border:0; } .signal-metrics span { display:block; font-size:10px; color:#647d84; margin-bottom:7px; } .signal-metrics strong { font-size:14px; overflow-wrap:anywhere; }
    .signal-notice { border-left:3px solid #dfb15e; background:#fff9ed; padding:8px 12px; margin-bottom:14px; } .signal-notice p { color:#78603a; font-size:11px; }
    .calibration { border:1px solid #d8e5e3; border-radius:8px; padding:12px; background:white; margin-bottom:16px; font-size:12px; } summary { cursor:pointer; font-weight:600; } .calibration > div { display:flex; align-items:end; gap:10px; flex-wrap:wrap; margin-top:10px; }
    label { display:flex; flex-direction:column; gap:6px; font-size:11px; color:#45636c; } input,select { padding:8px; border:1px solid #ccdddb; border-radius:6px; background:white; color:#193e49; min-width:0; } input[type=number] { width:145px; }
    button { border:1px solid #c6dcd8; border-radius:6px; padding:8px 12px; color:#096d70; background:white; cursor:pointer; } button:disabled { opacity:.4; cursor:default; } button:focus-visible, input:focus-visible, select:focus-visible { outline:2px solid #12868a; outline-offset:2px; }
    .chart-controls { display:flex; align-items:end; gap:12px; flex-wrap:wrap; margin:18px 0 12px; } .chart-controls > label:first-child { flex:1; min-width:140px; } .paging { display:flex; gap:6px; } .position { display:flex; flex-direction:column; gap:6px; margin:12px 0 18px; } input[type=range] { width:100%; accent-color:#12868a; padding:0; }
    .lead-list { display:grid; gap:14px; } .lead-card { border:1px solid #d7e5e2; border-radius:9px; overflow:hidden; background:white; } .lead-header { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:12px 14px 0; font-size:12px; } .lead-header strong { overflow-wrap:anywhere; } .lead-header span { color:#6b8287; font-size:10px; } i { display:inline-block; width:7px; height:7px; border-radius:50%; background:#12868a; margin-right:7px; } svg { display:block; width:100%; height:auto; min-height:150px; } svg text { font-size:11px; fill:#59747c; font-family:inherit; } footer { display:flex; flex-wrap:wrap; justify-content:space-between; gap:8px; border-top:1px solid #edf2f1; padding:9px 14px; font-size:10px; color:#5b747c; } .flat-warning { margin:8px 14px; color:#956726; } .chart-footnote { font-size:10px; margin-top:14px; }
    .signal-plot { overflow-x:auto; } .signal-plot svg { min-width:680px; }
    .auto-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:16px 0; font-size:16px; }
    .filter-note { padding:10px 14px; border-left:3px solid #cfaa57; background:#fff8e9; color:#6e5834; margin-bottom:16px; }
    .manual-controls { margin:14px 0; font-size:14px; }
    .navigator { margin:18px 0; padding:16px; border:1px solid #c9dfdb; border-radius:10px; background:white; }
    .navigator-heading,.navigation-actions { display:flex; gap:12px; align-items:center; justify-content:space-between; flex-wrap:wrap; font-size:13px; }
    .navigator-heading span { color:#58757c; } .overview { min-height:0; height:85px; width:100%; cursor:crosshair; margin:10px 0; }
    .navigator > input { width:100%; accent-color:#12868a; }
    .signal-plot { cursor:grab; touch-action:pan-y; user-select:none; } .signal-plot:active { cursor:grabbing; }
    .detected { padding:16px 20px; background:#f1f7f5; border-top:1px solid #dbe8e6; } .detected h4 { margin:0 0 8px; font-size:16px; }
    .feature-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:12px; margin:14px 0; } .feature-grid span { display:block; font-size:12px; color:#59737b; } .feature-grid strong { display:block; margin-top:6px; font-size:18px; }
    .statistics { padding:16px 20px; border-top:1px solid #dbe8e6; } .statistics h4 { margin:0 0 8px; font-size:16px; }
    .stats-scroll { overflow:auto; } table { width:100%; border-collapse:collapse; margin:12px 0; font-size:14px; font-variant-numeric:tabular-nums; }
    th,td { text-align:right; padding:10px 12px; border-bottom:1px solid #e0eae8; } th:first-child { text-align:left; } thead { background:#eef6f4; } tbody th { font-weight:500; }
    .morphology-panel { background:white; border:1px solid #cbdedb; border-radius:10px; padding:18px; margin:18px 0; }
    .morphology-heading,.morphology-scope { display:flex; align-items:center; gap:16px; justify-content:space-between; flex-wrap:wrap; } .morphology-heading h4 { margin:6px 0; font-size:21px; } .annotation-toggle { flex-direction:row; align-items:center; }
    .morphology-scope { margin:14px 0; font-size:13px; } .morphology-scope span { color:#58727a; }
    .morphology-metrics { display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr)); gap:10px; margin:16px 0; } .morphology-metrics > div { background:#f1f7f5; padding:12px; border-radius:7px; }
    .morphology-metrics span,.morphology-metrics small { display:block; font-size:12px; color:#536e75; } .morphology-metrics strong { display:block; margin:8px 0; font-size:22px; } .morphology-metrics strong small { display:inline; }
    .wave-legend { display:flex; gap:16px; font-weight:700; } .selected-measurements { background:#edf6f5; padding:10px; margin:12px 0; }
    svg .marker-label { font-size:14px; font-weight:700; paint-order:stroke; stroke:white; stroke-width:3px; }
    svg .interval-label { font-size:12px; font-weight:700; } svg g[role=button] { cursor:pointer; }
    .record-details { margin:16px 0; border:1px solid #d8e5e3; border-radius:8px; padding:14px; background:white; }
    .record-details > summary { font-size:14px; color:#244d58; }
    .zoom { display:flex; gap:8px; } .zoom button { font-size:14px; min-height:42px; font-weight:600; }
    label, .calibration, p, .signal-notice p { font-size:14px; }
    input,select,button { font-size:14px; min-height:42px; }
    .lead-header { padding:18px 20px 6px; font-size:18px; }
    .lead-header span, footer, .chart-footnote, .signal-metrics span { font-size:13px; }
    .signal-metrics strong { font-size:17px; }
    svg text { font-size:16px; fill:#284b55; }
    .signal-plot svg { min-width:800px; }
    .position { margin:16px 0 22px; }
    input[type=range] { min-height:28px; }
    @media(max-width:600px) { .ecg-viewer { padding:12px; } .signal-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); } .signal-heading { align-items:start; } h3 { font-size:20px; } .lead-header { flex-wrap:wrap; } svg { min-height:150px; } }
  `],
})
export class EcgWaveform {
  protected readonly Math = Math;
  private drag: { id:number; x:number; start:number; width:number } | null = null;
  readonly data = input.required<EcgPreview>();
  readonly documentId = input('');
  readonly calibrationValue = input<EcgCalibration>({ samplingRate:null, unit:null });
  readonly selectionChange = output<EcgSelection>();
  private readonly api = inject(Api);
  private readonly workspace = inject(WorkspaceStore);
  protected readonly analysisChannel = signal(0);
  protected readonly analysisRevision = signal(0);
  protected readonly increment = (value:number) => value + 1;
  protected readonly morphology = signal<EcgMorphology|null>(null);
  protected readonly morphologyLoading = signal(false);
  protected readonly morphologyError = signal('');
  protected readonly showAnnotations = signal(true);
  protected readonly selectedBeat = signal('');
  protected readonly selectedMeasurements = computed(() => this.morphology()?.beats.find(b => b.id === this.selectedBeat())?.measurements ?? null);
  protected readonly morphologyMetrics = computed(() => [
    {key:'heartRateBpm',label:'HR (oszacowanie)',unit:'/min'}, {key:'rrMs',label:'Odstęp RR',unit:'ms'},
    {key:'prMs',label:'Odstęp PR',unit:'ms'}, {key:'pDurationMs',label:'Czas P',unit:'ms'},
    {key:'qrsMs',label:'Szerokość QRS',unit:'ms'}, {key:'qtMs',label:'Odstęp QT',unit:'ms'},
    {key:'qtcFridericiaMs',label:'QTc Fridericii',unit:'ms'},
  ].filter(metric => this.morphology()?.timeBasis !== 'samples' || !['heartRateBpm','qtcFridericiaMs'].includes(metric.key)).map(metric => this.morphology()?.timeBasis === 'samples' ? {...metric,key:metric.key.replace('Ms','Samples'),unit:'próbek'} : metric).map(metric => ({...metric,value:this.morphology()?.summary[metric.key]?.median ?? null,min:this.morphology()?.summary[metric.key]?.min ?? null,max:this.morphology()?.summary[metric.key]?.max ?? null,count:this.morphology()?.summary[metric.key]?.count ?? 0})));
  readonly calibration = output<EcgCalibration>();
  protected readonly raw = signal(false);
  protected readonly filtered = computed(() => this.data().channels.some(c => c.displayProcessing?.length));
  protected readonly automaticWindow = computed(() => this.data().channels[0]?.automaticWindow ?? 1500);
  protected readonly first = computed(() => this.data().channels[0]);
  protected readonly needsCalibration = computed(() => this.data().channels.some(c => c.calibrationSource !== 'file' || !c.unit));
  protected readonly selected = signal('all');
  protected readonly windowSize = signal(500);
  protected readonly position = signal(0);
  protected recordingSeconds: number | null = null;
  protected durationRate():number|null {
    const duration=this.recordingSeconds;
    if (!duration || !Number.isFinite(duration) || duration<=0) return null;
    const rate=this.first().sampleCount/duration;
    return rate>0 && rate<=100000 ? rate : null;
  }
  protected applyDuration():void {
    const rate=this.durationRate();
    if(rate) { this.samplingRate=rate; this.applyCalibration(); }
  }
  protected samplingRate: number | null = null;
  protected unit = '';
  protected readonly maxLength = computed(() => Math.max(...this.data().channels.map(c => c.samples.length)));
  protected readonly visibleCount = computed(() => Math.min(this.windowSize() || this.maxLength(), this.maxLength()));
  protected readonly overviewPath = computed(() => {
    const c = this.first();
    const values = this.raw() ? c.samples : (c.displaySamples ?? c.samples);
    let min=Infinity,max=-Infinity;
    for (const v of values) if (v != null) { min=Math.min(min,v); max=Math.max(max,v); }
    return waveformPath(values, min, max, 64);
  });
  protected readonly maxStart = computed(() => this.windowSize() ? Math.max(0, this.maxLength() - this.windowSize()) : 0);
  protected readonly charts = computed(() => this.data().channels.map((channel, index) => ({ channel, index }))
    .filter(c => this.selected() === 'all' || this.selected() === c.index.toString()).map(({ channel, index }) => {
      const start = this.position();
      const source = this.raw() ? channel.samples : (channel.displaySamples ?? channel.samples);
      const values = source.slice(start, this.windowSize() ? start + this.windowSize() : undefined);
      const finite = values.filter((v): v is number => v !== null);
      let min = Infinity, max = -Infinity;
      for (const value of finite) { min = Math.min(min, value); max = Math.max(max, value); }
      if (!finite.length) { min = -1; max = 1; }
      const padding = (max - min || Math.abs(max) || 1) * .12;
      min -= padding; max += padding;
      const original = channel.samples.slice(start, this.windowSize() ? start + this.windowSize() : undefined);
      const windowStats = sampleStatistics(original);
      const metrics = [
        {label:'Minimum', window:windowStats.min, total:channel.statistics.min},
        {label:'Maksimum', window:windowStats.max, total:channel.statistics.max},
        {label:'Rozstęp amplitudy (max − min)', window:windowStats.peakToPeak, total:channel.statistics.peakToPeak ?? channel.statistics.max-channel.statistics.min},
        {label:'Średnia', window:windowStats.mean, total:channel.statistics.mean},
        {label:'Odchylenie standardowe (SD)', window:windowStats.standardDeviation, total:channel.statistics.standardDeviation},
        {label:'Wartość skuteczna (RMS)', window:windowStats.rms, total:channel.statistics.rms},
        {label:'Brakujące próbki', window:windowStats.missing, total:channel.statistics.missingSamples},
      ];
      const m = this.morphology();
      const eligible = this.showAnnotations() && m?.channelIndex === index && m.status === 'estimated';
      const markers = eligible ? m.markers.filter(marker => marker.sample >= start && marker.sample < start + values.length && source[marker.sample] != null).map(marker => ({...marker,
        x:64+(marker.sample-start)/Math.max(1,values.length-1)*900,
        y:18+(max-source[marker.sample]!)/(max-min||1)*300,
      })) : [];
      const bands = eligible ? m.intervals.filter(band => band.beatId === this.selectedBeat() && ['PR','QRS','QT'].includes(band.label) && band.startSample >= start && band.endSample < start+values.length).map((band,i) => ({...band,
        x:64+(band.startSample-start)/Math.max(1,values.length-1)*900, width:(band.endSample-band.startSample)/Math.max(1,values.length-1)*900,y:20+i*21,
      })) : [];
      return { channel, index, markers, bands, windowStats, metrics, sampleLength:original.length, min, max, hasSamples:values.length > 0, end:start + Math.max(0, values.length - 1), path:waveformPath(values, min, max, 300),
        yTicks:[0, .25, .5, .75, 1].map(t => ({ y:18 + t * 300, value:max - t * (max - min) })),
        xTicks:values.length ? [0, .25, .5, .75, 1].map(t => ({ x:64 + t * 900, value:(start + t * (values.length - 1)) / (channel.sampleRate || 1) })) : [] };
    }));

  protected readonly missingRate = (channel:EcgChannel) => !channel.sampleRate;
  protected readonly calibrationAiText = signal('');
  protected readonly calibrationAiLoading = signal(false);

  constructor() {
    effect(onCleanup => {
      const id=this.documentId(), role=this.workspace.role(), missing=this.data().channels.some(this.missingRate);
      this.calibrationAiText.set(''); this.calibrationAiLoading.set(false);
      if (!id || !missing) return;
      this.calibrationAiLoading.set(true);
      const subscription=this.api.assessEcgCalibration(role,id).subscribe({
        next:response => {this.calibrationAiText.set(response.answer);this.calibrationAiLoading.set(false);},
        error:() => {this.calibrationAiText.set('AI nie jest teraz dostępne. Analiza w próbkach działa niezależnie.');this.calibrationAiLoading.set(false);},
      });
      onCleanup(()=>subscription.unsubscribe());
    });
    effect((onCleanup) => {
      const id=this.documentId(), calibration=this.calibrationValue(), role=this.workspace.role();
      const selection={channelIndex:this.analysisChannel(),startSample:this.position(),sampleCount:this.visibleCount()};
      this.analysisRevision();
      this.morphology.set(null); this.morphologyError.set('');
      if(!id) return;
      untracked(() => this.selectionChange.emit(selection));
      this.morphologyLoading.set(true);
      let subscription:Subscription|undefined;
      const timer=setTimeout(() => { subscription=this.api.ecgMorphology(role,id,calibration,selection).subscribe({
        next:result => { this.morphology.set(result); this.morphologyLoading.set(false); const beat=result.beats.find(b=>b.rSample >= selection.startSample) ?? result.beats[0]; this.selectedBeat.set(beat?.id ?? ''); },
        error:error => { this.morphologyLoading.set(false); this.morphologyError.set(error?.error?.message ?? 'Nie udało się wykonać analizy PQRST.'); },
      }); },500);
      onCleanup(() => { clearTimeout(timer); subscription?.unsubscribe(); });
    });
    effect(() => { const data = this.data(); this.selected.set(data.channels.length > 12 ? '0' : 'all'); this.analysisChannel.set(0); this.position.set(0); this.windowSize.set(data.channels[0]?.automaticWindow ?? 1500);
    this.samplingRate = data.channels.find(c => c.calibrationSource === 'user')?.sampleRate ?? null;
    this.unit = data.channels.find(c => c.calibrationSource === 'user')?.unit ?? '';
  }); }
  protected waveColor(label:string):string { return ({P:'#7c3aed',Q:'#b45309',R:'#dc2626',S:'#2563eb',T:'#0f766e',PR:'#7c3aed',QRS:'#dc2626',QT:'#0f766e'} as Record<string,string>)[label] ?? '#475569'; }
  protected focusBeat(id:string):void {
    this.selectedBeat.set(id);
    const beat=this.morphology()?.beats.find(b=>b.id===id);
    if(beat && (beat.rSample < this.position() || beat.rSample >= this.position()+this.visibleCount()))
      this.position.set(Math.max(0,Math.min(this.maxStart(),Math.round(beat.rSample-this.visibleCount()/2))));
  }
  protected sourceLabel(source:string|undefined):string {
    return ({'duration':'obliczono z czasu rejestracji','metadata':'odczytano z pliku','time-column':'obliczono z kolumny czasu','header':'odczytano z nagłówka','user':'podane ręcznie','unknown':'brak danych — niewyznaczalne z samego sygnału'} as Record<string,string>)[source ?? 'unknown'] ?? source!;
  }
  protected overviewSeek(event:PointerEvent):void {
    const rect = (event.currentTarget as Element).getBoundingClientRect();
    const fraction = Math.max(0,Math.min(1,((event.clientX-rect.left)/rect.width*1000-64)/900));
    this.position.set(Math.max(0,Math.min(this.maxStart(),Math.round(fraction*this.maxLength()-this.visibleCount()/2))));
  }
  protected panStart(event:PointerEvent):void {
    if (event.button !== 0) return;
    const element = event.currentTarget as HTMLElement;
    const svg = element.querySelector('svg')!;
    this.drag = {id:event.pointerId,x:event.clientX,start:this.position(),width:svg.getBoundingClientRect().width*.9};
    element.setPointerCapture(event.pointerId);
  }
  protected panMove(event:PointerEvent):void {
    if (!this.drag || this.drag.id !== event.pointerId) return;
    this.position.set(Math.max(0,Math.min(this.maxStart(),Math.round(this.drag.start-(event.clientX-this.drag.x)/this.drag.width*this.visibleCount()))));
  }
  protected panEnd(event:PointerEvent):void {
    if (this.drag?.id !== event.pointerId) return;
    this.drag=null;
    const element=event.currentTarget as HTMLElement;
    if(element.hasPointerCapture(event.pointerId)) element.releasePointerCapture(event.pointerId);
  }
  protected wheel(event:WheelEvent):void {
    event.preventDefault();
    if (event.shiftKey || Math.abs(event.deltaX)>Math.abs(event.deltaY)) {
      const delta=event.deltaX || event.deltaY;
      this.position.set(Math.max(0,Math.min(this.maxStart(),this.position()+Math.round(delta*this.visibleCount()/1000))));
    } else this.zoom(event.deltaY<0?1:-1);
  }
  protected navigateKey(event:KeyboardEvent):void {
    if (!['ArrowLeft','ArrowRight','Home','End','+','-','='].includes(event.key)) return;
    event.preventDefault();
    if(event.key==='Home') this.position.set(0);
    else if(event.key==='End') this.position.set(this.maxStart());
    else if(event.key==='ArrowLeft') this.move(-1);
    else if(event.key==='ArrowRight') this.move(1);
    else this.zoom(event.key==='-'?-1:1);
  }
  protected setWindow(size:number):void {
    const center = this.position() + (this.windowSize() || this.maxLength()) / 2;
    this.windowSize.set(size);
    this.position.set(size ? Math.max(0, Math.min(this.maxStart(), Math.round(center - size / 2))) : 0);
  }
  protected zoom(direction:number):void {
    const sizes = [...new Set([100, 250, 500, this.automaticWindow(), 2500, 10000])].sort((a,b) => a-b).concat(0);
    const index = sizes.indexOf(this.windowSize());
    this.setWindow(sizes[Math.max(0, Math.min(sizes.length - 1, index - direction))]);
  }
  protected move(direction:number):void { this.position.set(Math.max(0, Math.min(this.maxStart(), this.position() + direction * this.visibleCount()))); }
  protected applyCalibration():void { this.calibration.emit({ samplingRate:this.samplingRate || null, unit:this.unit || null }); }
}
