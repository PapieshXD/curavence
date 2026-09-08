import { Component, computed, input } from '@angular/core';
import { ClinicalResultHistoryPoint, ClinicalResultParameter } from '../../../../core/models/medical';
import {
  clinicalNumericValue,
  clinicalResultStatus,
} from '../../../../core/utils/clinical-results';

interface TrendPointView extends ClinicalResultHistoryPoint {
  x: number;
  y: number;
  valueLabel: string;
  dateLabel: string;
  status: ReturnType<typeof clinicalResultStatus>;
}

interface TrendVisualization {
  points: TrendPointView[];
  polyline: string;
  lowLineY: number | null;
  highLineY: number | null;
  columns: string;
}

@Component({
  selector: 'app-result-trend-chart',
  templateUrl: './result-trend-chart.html',
  styleUrl: './result-trend-chart.scss',
  standalone: true
})
export class ResultTrendChart {
  readonly parameter = input.required<ClinicalResultParameter>();

  protected readonly visualization = computed<TrendVisualization | null>(() => {
    const parameter = this.parameter();
    const history = mergeCurrentValue(parameter).slice(-8);
    if (history.length < 2) return null;

    const reference = parameter.referenceRange;
    const values = history.map((point) => point.value);
    if (reference?.low != null) values.push(reference.low);
    if (reference?.high != null) values.push(reference.high);
    const rawMin = Math.min(...values);
    const rawMax = Math.max(...values);
    const padding = Math.max((rawMax - rawMin) * 0.15, Math.abs(rawMax || 1) * 0.03, 0.01);
    const domainMin = rawMin - padding;
    const domainMax = rawMax + padding;
    const yFor = (value: number): number => 18 + ((domainMax - value) / (domainMax - domainMin)) * 92;
    const points = history.map((point, index): TrendPointView => ({
      ...point,
      x: history.length === 1 ? 200 : 18 + (index / (history.length - 1)) * 364,
      y: yFor(point.value),
      valueLabel: formatNumber(point.value),
      dateLabel: formatDate(point.observedAt),
      status: clinicalResultStatus({
        ...parameter,
        value: point.value,
        numericValue: point.value,
        observedAt: point.observedAt,
      }),
    }));

    return {
      points,
      polyline: points.map((point) => `${point.x},${point.y}`).join(' '),
      lowLineY: reference?.low == null ? null : yFor(reference.low),
      highLineY: reference?.high == null ? null : yFor(reference.high),
      columns: `repeat(${points.length}, minmax(0, 1fr))`,
    };
  });

  protected readonly ariaLabel = computed(() => {
    const chart = this.visualization();
    const parameter = this.parameter();
    if (!chart) return `Brak historii pomiarów dla parametru ${parameter.name}`;
    const first = chart.points[0];
    const last = chart.points.at(-1)!;
    return `${parameter.name}: zmiana z ${first.valueLabel} do ${last.valueLabel}, ${chart.points.length} pomiarów.`;
  });
}

function mergeCurrentValue(parameter: ClinicalResultParameter): ClinicalResultHistoryPoint[] {
  const history = [...(parameter.history ?? [])];
  const currentValue = clinicalNumericValue(parameter);
  if (currentValue != null && parameter.observedAt && !history.some((point) => point.observedAt === parameter.observedAt)) {
    history.push({ observedAt: parameter.observedAt, value: currentValue });
  }
  return history
    .filter((point) => Number.isFinite(point.value) && !Number.isNaN(new Date(point.observedAt).getTime()))
    .sort((left, right) => new Date(left.observedAt).getTime() - new Date(right.observedAt).getTime());
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 }).format(value);
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('pl-PL', { day: '2-digit', month: 'short' }).format(new Date(value));
}
