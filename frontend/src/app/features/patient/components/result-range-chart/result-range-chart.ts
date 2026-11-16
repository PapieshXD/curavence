import { Component, computed, input } from '@angular/core';
import { ClinicalResultParameter } from '../../../../core/models/medical';
import {
  clinicalNumericValue,
  clinicalResultStatus,
  clinicalValueLabel,
} from '../../../../core/utils/clinical-results';

interface RangeVisualization {
  lowLabel: string | null;
  highLabel: string | null;
  normalStart: number;
  normalWidth: number;
  point: number;
  valueLabel: string;
  status: ReturnType<typeof clinicalResultStatus>;
}

@Component({
  selector: 'app-result-range-chart',
  templateUrl: './result-range-chart.html',
  styleUrl: './result-range-chart.scss',
})
export class ResultRangeChart {
  readonly parameter = input.required<ClinicalResultParameter>();

  protected readonly visualization = computed<RangeVisualization | null>(() => {
    const parameter = this.parameter();
    const value = clinicalNumericValue(parameter);
    const range = parameter.referenceRange;
    if (value == null || !range || (range.low == null && range.high == null)) return null;

    const boundary = range.low ?? range.high ?? value;
    const referenceSpan = range.low != null && range.high != null
      ? Math.max(Math.abs(range.high - range.low), 0.001)
      : Math.max(Math.abs(boundary) * 0.2, 1);
    const normalLow = range.low ?? (range.high ?? value) - referenceSpan;
    const normalHigh = range.high ?? (range.low ?? value) + referenceSpan;
    const visibleLow = Math.min(normalLow, value);
    const visibleHigh = Math.max(normalHigh, value);
    const padding = Math.max((visibleHigh - visibleLow) * 0.2, referenceSpan * 0.12, 0.01);
    const domainLow = visibleLow - padding;
    const domainHigh = visibleHigh + padding;
    const percent = (current: number): number => clamp(((current - domainLow) / (domainHigh - domainLow)) * 100);
    const normalStart = range.low == null ? 0 : percent(range.low);
    const normalEnd = range.high == null ? 100 : percent(range.high);

    return {
      lowLabel: range.low == null ? null : formatNumber(range.low),
      highLabel: range.high == null ? null : formatNumber(range.high),
      normalStart,
      normalWidth: Math.max(normalEnd - normalStart, 1),
      point: percent(value),
      valueLabel: clinicalValueLabel(parameter),
      status: clinicalResultStatus(parameter),
    };
  });

  protected readonly ariaLabel = computed(() => {
    const chart = this.visualization();
    const parameter = this.parameter();
    if (!chart) return `Brak zakresu referencyjnego dla parametru ${parameter.name}`;
    return `${parameter.name}: ${chart.valueLabel}. Pozycja wyniku względem zakresu referencyjnego.`;
  });
}

function clamp(value: number): number {
  return Math.min(100, Math.max(0, value));
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 }).format(value);
}
