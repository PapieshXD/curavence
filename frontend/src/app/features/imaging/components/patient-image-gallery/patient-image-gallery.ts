import { Component, ElementRef, OnDestroy, ViewChild, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EMPTY, catchError, from, mergeMap, tap, timeout } from 'rxjs';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { ImagingAnalysis, ImagingSeries } from '../../../../core/models/imaging';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';

interface Layer { key: string; url: string; failed: boolean; alignmentKey?: string | null; }
interface DisplayLayer extends Layer { urls: string[]; members: string[]; sourceCount: number; }
interface Group { id: string; layers: Layer[]; failed: boolean; }

@Component({
  selector: 'app-patient-image-gallery', imports: [FormsModule, AppIcon],
  templateUrl: './patient-image-gallery.html', styleUrl: './patient-image-gallery.scss',
})
export class PatientImageGallery implements OnDestroy {
  private readonly api = inject(Api);
  private readonly workspace = inject(WorkspaceStore);
  readonly studyId = input.required<string>();
  readonly series = input.required<ImagingSeries[]>();
  readonly analysis = input<ImagingAnalysis | null>(null);
  readonly active = input(true);
  readonly clinical = input(false);
  readonly readReport = output<void>();
  @ViewChild('player') private player?: ElementRef<HTMLElement>;
  @ViewChild('enlarged') private dialog?: ElementRef<HTMLDialogElement>;
  protected readonly groups = signal<Group[]>([]);
  protected readonly loading = signal(true);
  protected readonly revision = signal(0);
  protected readonly selectedKey = signal('');
  protected readonly playing = signal(false);
  protected readonly largeTiles = signal(false);
  protected readonly originals = signal(true);
  private timer?: ReturnType<typeof setInterval>;
  protected readonly sourceLayers = computed(() => this.groups().flatMap(group => group.layers));
  protected readonly layers = computed<DisplayLayer[]>(() => {
    const buckets = new Map<string, Layer[]>();
    for (const layer of this.sourceLayers()) {
      const key = !this.originals() && layer.alignmentKey ? layer.alignmentKey : layer.key;
      const bucket = buckets.get(key) ?? []; bucket.push(layer); buckets.set(key, bucket);
    }
    return [...buckets.values()].map(members => {
      const urls = members.map(layer => layer.url).filter(Boolean);
      return { ...members[0], url: urls[0] ?? '', urls, members: members.map(layer => layer.key),
        sourceCount: members.length, failed: members.some(layer => layer.failed) };
    });
  });
  protected readonly loaded = computed(() => this.sourceLayers().filter(layer => layer.url).length);
  protected readonly failed = computed(() => this.groups().some(group => group.failed) || this.layers().some(layer => layer.failed));
  protected readonly selectedIndex = computed(() => this.layers().findIndex(layer => layer.members.includes(this.selectedKey())));
  protected readonly selected = computed<DisplayLayer | undefined>(() => this.layers()[this.selectedIndex()]);

  constructor() {
    effect(() => { if (this.clinical()) this.originals.set(true); });
    effect(() => { if (!this.active()) this.pause(); });
    effect(onCleanup => {
      const studyId = this.studyId(); const catalog = this.series(); const role = this.workspace.role();
      this.revision();
      this.pause(); this.dialog?.nativeElement.close();
      const first = catalog.find(series => series.imageCount > 0);
      this.selectedKey.set(first ? `${first.id}:${Math.floor(first.imageCount / 2)}` : ''); this.loading.set(true);
      this.groups.set(catalog.map(series => ({ id: series.id, failed: false,
        layers: Array.from({ length: series.imageCount }, (_, index) => ({ key: `${series.id}:${index}`, url: '', failed: false })),
      })));
      const urls: string[] = [];
      const requests = from(catalog).pipe(
        mergeMap(series => this.api.imagingOverview(role, studyId, series.id).pipe(timeout(180_000),
          mergeMap(overview => {
            const prepared = overview.series.find(item => item.id === series.id);
            if (!prepared?.frames.length) throw new Error('Brak obrazów serii');
            this.groups.update(groups => groups.map(group => group.id === series.id ? { ...group,
              layers: prepared.frames.map(frame => ({ key: `${series.id}:${frame.index}`, url: '', failed: false, alignmentKey: frame.alignmentKey })),
            } : group));
            return from(prepared.frames).pipe(mergeMap(frame => {
              const key = `${series.id}:${frame.index}`;
              const update = (url: string, failed: boolean) => this.groups.update(groups => groups.map(group => group.id === series.id
                ? { ...group, layers: group.layers.map(layer => layer.key === key ? { ...layer, url, failed } : layer) } : group));
              return this.api.imagingPreviewImage(role, studyId, frame.imageUrl).pipe(timeout(120_000),
                tap(blob => { const url = URL.createObjectURL(blob); urls.push(url); update(url, false); }),
                catchError(() => { update('', true); return EMPTY; }));
            }, 4));
          }),
          catchError(() => {
            this.groups.update(groups => groups.map(group => group.id === series.id
              ? { ...group, failed: true, layers: group.layers.map(layer => ({ ...layer, failed: true })) } : group));
            return EMPTY;
          }),
        ), 2),
      ).subscribe({ complete: () => this.loading.set(false) });
      onCleanup(() => { this.pause(); requests.unsubscribe(); urls.forEach(url => URL.revokeObjectURL(url)); });
    });
  }

  protected open(key: string): void {
    this.pause(); this.selectedKey.set(key);
    this.player?.nativeElement.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' });
    this.player?.nativeElement.focus({ preventScroll: true });
  }
  protected enlarge(): void { this.pause(); this.dialog?.nativeElement.showModal(); }
  protected close(): void { this.dialog?.nativeElement.close(); }
  protected setOriginals(value: boolean): void { this.pause(); this.originals.set(value); }
  protected move(index: number): void {
    this.pause();
    const layer = this.layers()[Number(index)];
    if (layer) this.selectedKey.set(layer.key);
  }
  protected pause(): void { if (this.timer) clearInterval(this.timer); this.timer = undefined; this.playing.set(false); }
  protected togglePlayback(): void {
    if (this.playing()) { this.pause(); return; }
    if (this.selectedIndex() >= this.layers().length - 1) this.move(0);
    if (!this.selected()?.url) return;
    this.playing.set(true);
    this.timer = setInterval(() => {
      const next = this.layers()[this.selectedIndex() + 1];
      if (!next?.url) { this.pause(); return; }
      this.selectedKey.set(next.key);
      if (this.selectedIndex() === this.layers().length - 1) this.pause();
    }, 500);
  }
  ngOnDestroy(): void { this.pause(); }
}
