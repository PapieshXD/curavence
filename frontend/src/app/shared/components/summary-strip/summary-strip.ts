import { Component, input } from '@angular/core';

export interface SummaryItem {
  label: string;
  value: string;
  tone?: 'default' | 'warning' | 'danger';
}

@Component({
  selector: 'app-summary-strip',
  template: `
    <section class="summary-strip" aria-label="Podsumowanie">
      <div class="summary-caption">{{ caption() }}</div>
      @for (item of items(); track item.label) {
        <div class="summary-item" [class]="item.tone ?? 'default'">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      }
    </section>
  `,
})
export class SummaryStrip {
  readonly caption = input('Dzisiaj');
  readonly items = input.required<SummaryItem[]>();
}
