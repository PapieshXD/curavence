import { Component, computed, input, output } from '@angular/core';
import { ImagingSeries } from '../../../../core/models/imaging';

export function isTechnicalSeries(series: ImagingSeries): boolean {
  return /localizer|scout|topogram|survey|locator/i.test(`${series.description ?? ''} ${series.sequenceName ?? ''}`);
}

@Component({
  selector: 'app-study-series-rail',
  template: `
    <section aria-label="Aktywne obrazy" class="material">
      <header><div><strong>Aktywne obrazy</strong><p>{{ visible().length }} z {{ series().length }} grup · {{ frameCount() }} obrazów</p></div>
        <button type="button" (click)="restore.emit()">Pokaż wszystkie obrazy</button>
        <button type="button" (click)="hideTechnical.emit()">Ukryj pomocnicze</button>
      </header>
      <p class="hint">Ukrycie zmienia tylko ten widok. Materiał badania i zakres analizy pozostają zapisane.</p>
      <div class="cards">
        @for (item of visible(); track item.id; let index = $index) {
          <article [class.selected]="selected() === item.id">
            <button class="open" type="button" (click)="choose.emit(item.id)">
              <span>{{ patient() ? (technical(item) ? 'Obrazy pomocnicze' : 'Obrazy badania ' + (index + 1)) : item.description || 'Obrazy badania' }}</span>
              <small>{{ item.imageCount }} obrazów · {{ status(item.seriesInstanceUid) }}</small>
              @if (!patient()) { <small>{{ item.modality }} · {{ item.mprEligible ? 'MPR dostępne' : 'Widok 2D' }}</small> }
              <span class="action">Otwórz przekroje →</span>
            </button>
            <button class="hide" type="button" (click)="hide.emit(item.id)" [attr.aria-label]="'Ukryj obrazy ' + (index + 1)">Ukryj w widoku</button>
          </article>
        } @empty { <p>Wszystkie obrazy są ukryte. Przywróć je przyciskiem „Pokaż wszystkie obrazy”.</p> }
      </div>
    </section>`,
  styles: [`
    :host{display:block;margin:20px 0;color:#14343d}.material{padding:20px;border:1px solid #dce6e8;border-radius:10px;background:#fff}
    header{display:flex;align-items:center;gap:12px;flex-wrap:wrap}header div{flex:1;min-width:180px}p{font-size:13px;color:#64777d;margin:6px 0}
    button{font:inherit;cursor:pointer;border:1px solid #bed3d5;border-radius:6px;padding:9px 12px;background:#fff;color:#10686a}
    button:focus-visible{outline:3px solid #168486;outline-offset:3px}.hint{margin:12px 0 18px}.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(220px,100%),1fr));gap:12px}
    article{border:1px solid #dce6e8;border-radius:8px;overflow:hidden}.selected{border-color:#168486;background:#edf7f6}
    .open{display:flex;flex-direction:column;align-items:flex-start;gap:10px;border:0;background:transparent;width:100%;text-align:left;min-height:130px;overflow-wrap:anywhere}
    small{color:#64777d}.action{font-size:12px;margin-top:auto}.hide{font-size:12px;border:0;border-top:1px solid #dce6e8;border-radius:0;width:100%;background:transparent}
  `],
})
export class StudySeriesRail {
  readonly series = input<ImagingSeries[]>([]);
  readonly hidden = input<string[]>([]);
  readonly selected = input('');
  readonly patient = input(true);
  readonly results = input<{seriesInstanceUid: string; analysisStatus: string}[]>([]);
  readonly choose = output<string>();
  readonly hide = output<string>();
  readonly restore = output<void>();
  readonly hideTechnical = output<void>();
  readonly visible = computed(() => this.series().filter(s => !this.hidden().includes(s.id)));
  readonly frameCount = computed(() => this.visible().reduce((sum, s) => sum + s.imageCount, 0));
  readonly technical = isTechnicalSeries;
  status(uid: string): string {
    const status = this.results().find(s => s.seriesInstanceUid === uid)?.analysisStatus;
    return ({ANALYZED: 'Analiza dostępna', PARTIALLY_ANALYZED: 'Analiza częściowa', DISCOVERED: 'Oczekuje na analizę', FAILED: 'Nie oceniono', EXCLUDED_TECHNICAL: 'Materiał pomocniczy',
      EXCLUDED_BY_SCOPE: 'Poza zakresem analizy', UNSUPPORTED: 'Poza obsługą AI', ELIGIBLE: 'Do analizy'} as Record<string, string>)[status ?? ''] ?? 'Obrazy dostępne';
  }
}
