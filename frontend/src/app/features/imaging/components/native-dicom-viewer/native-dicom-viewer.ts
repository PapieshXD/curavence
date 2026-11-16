import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ImagingSeries, SaveImagingAnnotation, ViewerSession } from '../../../../core/models/imaging';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';

type CoreModule = typeof import('@cornerstonejs/core');
type ToolsModule = typeof import('@cornerstonejs/tools');
type DicomLoaderModule = typeof import('@cornerstonejs/dicom-image-loader');
type ViewerLayout = 'stack' | 'mpr' | 'volume';
type DicomTag = { Value?: unknown[] };
type DicomJson = Record<string, DicomTag>;

interface CornerstoneRuntime {
  core: CoreModule;
  tools: ToolsModule;
  dicomLoader: DicomLoaderModule;
}

interface DicomSeriesOption {
  uid: string;
  number: number;
  description: string;
  modality: string;
  imageCount: number;
  backendId: string | null;
  mprEligible: boolean;
  volumeEligible: boolean;
  eligibilityReason: string | null;
}

interface ViewerTool {
  id: string;
  name: string;
  key: string;
  help: string;
}

const PRIMARY_TOOLS: ViewerTool[] = [
  { id: 'WindowLevel', name: 'Jasność / kontrast', key: 'W', help: 'Przeciągnij: poziomo zmieniasz kontrast, pionowo jasność.' },
  { id: 'Pan', name: 'Przesuń', key: 'P', help: 'Przeciągnij obraz, aby obejrzeć wybrany obszar.' },
  { id: 'Zoom', name: 'Powiększ', key: 'Z', help: 'Przeciągnij w górę lub w dół. Kółko zmienia przekrój.' },
  { id: 'Length', name: 'Długość', key: 'L', help: 'Wskaż dwa punkty, aby zmierzyć odległość w danych DICOM.' },
  { id: 'Angle', name: 'Kąt', key: 'A', help: 'Wskaż ramiona kąta; wynik zostanie zapisany na obrazie.' },
  { id: 'EllipticalROI', name: 'Obszar eliptyczny', key: 'E', help: 'Zakreśl obszar, aby odczytać jego rozmiar i statystyki.' },
  { id: 'RectangleROI', name: 'Obszar prostokątny', key: 'R', help: 'Zakreśl prostokąt do pomiaru obszaru.' },
  { id: 'Probe', name: 'Punkt', key: 'B', help: 'Kliknij, aby odczytać wartość piksela lub wokselu.' },
  { id: 'ArrowAnnotate', name: 'Strzałka i opis', key: 'N', help: 'Wskaż miejsce i dodaj krótki opis.' },
  { id: 'Magnify', name: 'Lupa', key: 'M', help: 'Kliknij i przeciągnij lupę po obrazie.' },
];

let runtimePromise: Promise<CornerstoneRuntime> | null = null;
let toolsRegistered = false;

async function loadRuntime(): Promise<CornerstoneRuntime> {
  if (!runtimePromise) {
    runtimePromise = (async () => {
      const core = await import('@cornerstonejs/core').catch(error => {
        throw new Error(`Nie udało się załadować renderera obrazu: ${error instanceof Error ? error.message : String(error)}`);
      });
      const tools = await import('@cornerstonejs/tools').catch(error => {
        throw new Error(`Nie udało się załadować narzędzi pomiarowych: ${error instanceof Error ? error.message : String(error)}`);
      });
      const dicomLoader = await import('@cornerstonejs/dicom-image-loader').catch(error => {
        throw new Error(`Nie udało się załadować dekodera DICOM: ${error instanceof Error ? error.message : String(error)}`);
      });
      await core.init();
      dicomLoader.internal.setOptions({
        maxWebWorkers: Math.max(1, Math.min(globalThis.navigator?.hardwareConcurrency || 2, 4)),
      });
      dicomLoader.wadors.register();
      dicomLoader.wadouri.register();
      core.getWebWorkerManager().registerWorker(
        'dicomImageLoader',
        () => new Worker(new URL('../../workers/dicom-decoder.worker', import.meta.url), { type: 'module' }),
        {
          maxWorkerInstances: Math.max(1, Math.min(globalThis.navigator?.hardwareConcurrency || 2, 4)),
          overwrite: true,
        },
      );
      await tools.init();
      if (!toolsRegistered) {
        [
          tools.WindowLevelTool,
          tools.PanTool,
          tools.ZoomTool,
          tools.StackScrollTool,
          tools.LengthTool,
          tools.AngleTool,
          tools.EllipticalROITool,
          tools.RectangleROITool,
          tools.ProbeTool,
          tools.ArrowAnnotateTool,
          tools.MagnifyTool,
          tools.PlanarRotateTool,
          tools.CrosshairsTool,
          tools.TrackballRotateTool,
        ].forEach(tool => tools.addTool(tool));
        toolsRegistered = true;
      }
      return { core, tools, dicomLoader };
    })();
  }
  return runtimePromise;
}

function dicomValue(item: DicomJson, tag: string): unknown {
  return item[tag]?.Value?.[0];
}

function dicomText(item: DicomJson, tag: string, fallback = ''): string {
  const value = dicomValue(item, tag);
  if (value && typeof value === 'object' && 'Alphabetic' in value) {
    return String((value as { Alphabetic?: unknown }).Alphabetic ?? fallback);
  }
  return value == null ? fallback : String(value);
}

function dicomNumber(item: DicomJson, tag: string, fallback = 0): number {
  const value = Number(dicomValue(item, tag));
  return Number.isFinite(value) ? value : fallback;
}

@Component({
  selector: 'app-native-dicom-viewer',
  imports: [FormsModule],
  templateUrl: './native-dicom-viewer.html',
  styleUrl: './native-dicom-viewer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NativeDicomViewer implements AfterViewInit, OnDestroy {
  private readonly api = inject(Api);
  private readonly workspace = inject(WorkspaceStore);
  readonly studyId = input.required<string>();
  readonly initialSeriesId = input('');
  readonly patientMode = input(false);
  readonly hiddenSeriesIds = input<string[]>([]);
  readonly seriesCatalog = input<ImagingSeries[]>([]);

  @ViewChild('stackViewport') private stackElement?: ElementRef<HTMLDivElement>;
  @ViewChild('axialViewport') private axialElement?: ElementRef<HTMLDivElement>;
  @ViewChild('coronalViewport') private coronalElement?: ElementRef<HTMLDivElement>;
  @ViewChild('sagittalViewport') private sagittalElement?: ElementRef<HTMLDivElement>;
  @ViewChild('volumeViewport') private volumeElement?: ElementRef<HTMLDivElement>;
  @ViewChild('viewerShell') private viewerShell?: ElementRef<HTMLDivElement>;

  protected readonly loading = signal(true);
  protected readonly progressText = signal('Tworzymy bezpieczną sesję badania…');
  protected readonly error = signal('');
  protected readonly warning = signal('');
  protected readonly session = signal<ViewerSession | null>(null);
  protected readonly series = signal<DicomSeriesOption[]>([]);
  protected readonly selectedSeriesUid = signal('');
  protected readonly imageIds = signal<string[]>([]);
  protected readonly frameIndex = signal(0);
  protected readonly layout = signal<ViewerLayout>('stack');
  protected readonly activeTool = signal('WindowLevel');
  protected readonly cinePlaying = signal(false);
  protected readonly cineFps = signal(8);
  protected readonly inverted = signal(false);
  protected readonly annotationStatus = signal('');
  protected readonly windowLabel = signal('');
  protected readonly showHelp = signal(true);
  protected get tools(): ViewerTool[] { return this.patientMode() ? PRIMARY_TOOLS.slice(0, 3) : PRIMARY_TOOLS; }
  protected readonly selectedSeries = computed(() => this.series().find(item => item.uid === this.selectedSeriesUid()) ?? null);
  protected readonly currentTool = computed(() => this.tools.find(tool => tool.id === this.activeTool()) ?? this.tools[0]);
  protected readonly frameLabel = computed(() => `${Math.min(this.frameIndex() + 1, this.imageIds().length || 1)} / ${this.imageIds().length || 1}`);

  private runtime: CornerstoneRuntime | null = null;
  private renderingEngine: InstanceType<CoreModule['RenderingEngine']> | null = null;
  private viewport: InstanceType<CoreModule['StackViewport']> | null = null;
  private toolGroup: ReturnType<ToolsModule['ToolGroupManager']['createToolGroup']> = undefined;
  private renderingEngineId = '';
  private toolGroupId = '';
  private disposed = false;
  private generation = 0;
  private annotationListener?: EventListener;
  private imageListener?: EventListener;
  private readonly savedAnnotationUids = new Set<string>();

  ngAfterViewInit(): void {
    void this.bootstrap();
  }

  ngOnDestroy(): void {
    this.disposed = true;
    this.destroyViewer();
  }

  @HostListener('window:resize')
  protected resize(): void {
    this.renderingEngine?.resize(true, true);
  }

  @HostListener('window:keydown', ['$event'])
  protected keyboard(event: KeyboardEvent): void {
    if (event.ctrlKey || event.metaKey || event.altKey || /INPUT|TEXTAREA|SELECT/.test((event.target as HTMLElement)?.tagName ?? '')) return;
    const tool = this.tools.find(item => item.key.toLowerCase() === event.key.toLowerCase());
    if (tool) {
      event.preventDefault();
      this.selectTool(tool.id);
    }
    if (event.key === ' ') {
      event.preventDefault();
      this.toggleCine();
    }
  }

  protected async retry(): Promise<void> {
    this.error.set('');
    this.warning.set('');
    await this.bootstrap();
  }

  protected async chooseSeries(uid: string): Promise<void> {
    if (!uid || uid === this.selectedSeriesUid()) return;
    this.selectedSeriesUid.set(uid);
    await this.loadSelectedSeries(this.layout());
  }

  protected async setLayout(layout: ViewerLayout): Promise<void> {
    if (layout === this.layout() && this.renderingEngine) return;
    const selected = this.selectedSeries();
    if (layout === 'mpr' && !selected?.mprEligible) {
      this.warning.set(selected?.eligibilityReason || 'Ta seria nie ma geometrii potrzebnej do rekonstrukcji MPR. Wybierz serię przekrojową.');
      return;
    }
    if (layout === 'volume' && !selected?.volumeEligible) {
      this.warning.set(selected?.eligibilityReason || 'Ta seria nie zawiera kompletnej objętości do rekonstrukcji 3D.');
      return;
    }
    this.warning.set('');
    this.layout.set(layout);
    await new Promise(resolve => setTimeout(resolve));
    try {
      await this.renderLayout(layout);
      this.loading.set(false);
    } catch (error) {
      this.loading.set(false);
      this.error.set(error instanceof Error ? error.message : 'Nie udało się zbudować wybranego widoku badania.');
    }
  }

  protected selectTool(toolName: string): void {
    if (!this.toolGroup || !this.runtime) return;
    const previous = this.activeTool();
    if (this.toolGroup.hasTool(previous)) this.toolGroup.setToolPassive(previous, { removeAllBindings: true });
    this.activeTool.set(toolName);
    if (this.toolGroup.hasTool(toolName)) {
      this.toolGroup.setToolActive(toolName, { bindings: [{ mouseButton: this.runtime.tools.Enums.MouseBindings.Primary }] });
    }
  }

  protected changeFrame(index: number): void {
    if (!this.viewport || this.layout() !== 'stack') return;
    const target = Math.max(0, Math.min(this.imageIds().length - 1, Number(index)));
    void this.viewport.setImageIdIndex(target).then(() => {
      this.frameIndex.set(target);
      this.viewport?.render();
    });
  }

  protected toggleCine(): void {
    const element = this.stackElement?.nativeElement;
    if (!this.runtime || !element || this.layout() !== 'stack' || this.imageIds().length < 2) return;
    if (this.cinePlaying()) {
      this.runtime.tools.utilities.cine.stopClip(element, { stopDynamicCine: true });
      this.cinePlaying.set(false);
    } else {
      this.runtime.tools.utilities.cine.playClip(element, { framesPerSecond: this.cineFps(), loop: true });
      this.cinePlaying.set(true);
    }
  }

  protected updateCineSpeed(value: number): void {
    this.cineFps.set(Number(value));
    if (this.cinePlaying()) {
      this.toggleCine();
      this.toggleCine();
    }
  }

  protected resetView(): void {
    if (this.layout() === 'stack' && this.viewport) {
      this.viewport.resetProperties();
      this.viewport.resetCamera();
      this.inverted.set(false);
      this.viewport.render();
      return;
    }
    this.renderingEngine?.getViewports().forEach(viewport => {
      viewport.resetCamera();
      viewport.render();
    });
  }

  protected invert(): void {
    if (!this.viewport) return;
    const inverted = !this.inverted();
    this.inverted.set(inverted);
    this.viewport.setProperties({ ...this.viewport.getProperties(), invert: inverted });
    this.viewport.render();
  }

  protected flip(direction: 'horizontal' | 'vertical'): void {
    if (!this.viewport) return;
    const camera = this.viewport.getCamera();
    this.viewport.setCamera(direction === 'horizontal'
      ? { flipHorizontal: !camera.flipHorizontal }
      : { flipVertical: !camera.flipVertical });
    this.viewport.render();
  }

  protected rotate(delta: number): void {
    if (!this.viewport) return;
    const camera = this.viewport.getCamera();
    this.viewport.setCamera({ rotation: ((camera.rotation ?? 0) + delta + 360) % 360 });
    this.viewport.render();
  }

  protected fullscreen(): void {
    this.viewerShell?.nativeElement.requestFullscreen().then(() => this.resize()).catch(() => {
      this.warning.set('Pełny ekran jest zablokowany przez przeglądarkę.');
    });
  }

  private async bootstrap(): Promise<void> {
    const generation = ++this.generation;
    this.loading.set(true);
    this.progressText.set('Ładujemy silnik diagnostyczny…');
    this.destroyViewer();
    try {
      this.runtime = await loadRuntime();
      this.progressText.set('Tworzymy bezpieczną sesję badania…');
      const session = await firstValueFrom(this.api.createViewerSession(this.workspace.role(), this.studyId(), true));
      if (this.disposed || generation !== this.generation) return;
      this.session.set(session);
      this.progressText.set('Pobieramy listę serii DICOM…');
      const options = await this.fetchSeries(session);
      if (!options.length) throw new Error('Badanie nie zawiera serii możliwych do wyświetlenia.');
      this.series.set(options);
      this.selectedSeriesUid.set(options.find(s => s.backendId === this.initialSeriesId())?.uid ?? options.find(s => !/localizer|scout|topogram|survey|locator/i.test(s.description))?.uid ?? options[0].uid);
      await this.loadSelectedSeries('stack');
    } catch (error) {
      if (this.disposed || generation !== this.generation) return;
      this.loading.set(false);
      this.error.set(error instanceof Error ? error.message : 'Nie udało się uruchomić przeglądarki DICOM.');
    }
  }

  private async fetchSeries(session: ViewerSession): Promise<DicomSeriesOption[]> {
    const root = this.sessionRoot(session);
    const response = await fetch(`${root}/studies/${encodeURIComponent(session.studyInstanceUid)}/series?includefield=all`, {
      credentials: 'include', headers: { Accept: 'application/dicom+json' },
    });
    if (!response.ok) throw new Error(response.status === 401 ? 'Sesja badania wygasła. Uruchom viewer ponownie.' : 'Nie udało się pobrać listy serii z archiwum DICOM.');
    const payload = await response.json() as DicomJson[];
    return payload.map((item, index) => {
      const uid = dicomText(item, '0020000E');
      const known = this.seriesCatalog().find(series => series.seriesInstanceUid === uid);
      return {
        uid,
        number: dicomNumber(item, '00200011', known?.seriesNumber ?? index + 1),
        description: dicomText(item, '0008103E', known?.description || `Seria ${index + 1}`),
        modality: dicomText(item, '00080060', known?.modality || 'DICOM'),
        imageCount: dicomNumber(item, '00201209', known?.imageCount ?? 0),
        backendId: known?.id ?? null,
        mprEligible: known?.mprEligible ?? false,
        volumeEligible: known?.volumeRenderingEligible ?? false,
        eligibilityReason: known?.eligibilityReason ?? null,
      };
    }).filter(item => item.uid && (!item.backendId || !this.hiddenSeriesIds().includes(item.backendId))).sort((a, b) => a.number - b.number);
  }

  private async loadSelectedSeries(layout: ViewerLayout): Promise<void> {
    const session = this.session();
    const uid = this.selectedSeriesUid();
    if (!session || !uid) return;
    this.loading.set(true);
    this.error.set('');
    this.progressText.set('Porządkujemy przekroje według danych DICOM…');
    try {
      const root = this.sessionRoot(session);
      const geometryFields = ['00200032', '00200037', '00280030', '00200052', '00180050', '00180088'];
      const includeFields = ['all', ...geometryFields].map(field => `includefield=${field}`).join('&');
      const response = await fetch(`${root}/studies/${encodeURIComponent(session.studyInstanceUid)}/series/${encodeURIComponent(uid)}/instances?${includeFields}`, {
        credentials: 'include', headers: { Accept: 'application/dicom+json' },
      });
      if (!response.ok) throw new Error('Nie udało się pobrać klatek wybranej serii.');
      const instances = await response.json() as DicomJson[];
      instances.sort((a, b) => dicomNumber(a, '00200013') - dicomNumber(b, '00200013'));
      const imageIds: string[] = [];
      for (const instance of instances) {
        const sop = dicomText(instance, '00080018');
        const frames = Math.max(1, dicomNumber(instance, '00280008', 1));
        for (let frame = 1; frame <= frames; frame++) {
          const imageId = `wadors:${root}/studies/${encodeURIComponent(session.studyInstanceUid)}/series/${encodeURIComponent(uid)}/instances/${encodeURIComponent(sop)}/frames/${frame}`;
          this.runtime?.dicomLoader.wadors.metaDataManager.add(
            imageId,
            instance as unknown as Parameters<DicomLoaderModule['wadors']['metaDataManager']['add']>[1],
          );
          imageIds.push(imageId);
        }
      }
      if (!imageIds.length) throw new Error('Wybrana seria nie zawiera klatek możliwych do wyświetlenia.');
      this.imageIds.set(imageIds);
      this.frameIndex.set(Math.floor(imageIds.length / 2));
      this.layout.set(layout);
      await new Promise(resolve => setTimeout(resolve));
      await this.renderLayout(layout);
      this.loading.set(false);
    } catch (error) {
      this.loading.set(false);
      this.error.set(error instanceof Error ? error.message : 'Nie udało się otworzyć serii DICOM.');
    }
  }

  private async renderLayout(layout: ViewerLayout): Promise<void> {
    if (!this.runtime) return;
    this.destroyViewer();
    this.loading.set(true);
    this.progressText.set(layout === 'stack' ? 'Renderujemy przekrój…' : 'Budujemy rekonstrukcję objętościową…');
    const engineId = `curavence-engine-${crypto.randomUUID()}`;
    const groupId = `curavence-tools-${crypto.randomUUID()}`;
    this.renderingEngineId = engineId;
    this.toolGroupId = groupId;
    const engine = new this.runtime.core.RenderingEngine(engineId);
    this.renderingEngine = engine;

    if (layout === 'stack') {
      const element = this.stackElement?.nativeElement;
      if (!element) throw new Error('Nie udało się przygotować obszaru obrazu.');
      const viewportId = `${engineId}-stack`;
      engine.enableElement({ element, viewportId, type: this.runtime.core.Enums.ViewportType.STACK, defaultOptions: { background: [0.025, 0.045, 0.05] } });
      const viewport = engine.getViewport(viewportId) as InstanceType<CoreModule['StackViewport']>;
      this.viewport = viewport;
      this.configureTools([viewportId], 'stack');
      this.bindStackEvents(element);
      const selectedImageId = this.imageIds()[this.frameIndex()];
      await Promise.race([
        this.runtime.core.imageLoader.loadAndCacheImage(selectedImageId, { priority: 0, requestType: 'interaction' }),
        new Promise((_resolve, reject) => setTimeout(() => reject(new Error('Archiwum DICOM nie zwróciło obrazu w wymaganym czasie.')), 45_000)),
      ]);
      await viewport.setStack(this.imageIds(), this.frameIndex());
      this.applyReadableWindow(viewport);
      viewport.render();
      return;
    }

    const definitions = layout === 'mpr'
      ? [
          { element: this.axialElement?.nativeElement, id: `${engineId}-axial`, orientation: this.runtime.core.Enums.OrientationAxis.AXIAL },
          { element: this.coronalElement?.nativeElement, id: `${engineId}-coronal`, orientation: this.runtime.core.Enums.OrientationAxis.CORONAL },
          { element: this.sagittalElement?.nativeElement, id: `${engineId}-sagittal`, orientation: this.runtime.core.Enums.OrientationAxis.SAGITTAL },
        ]
      : [{ element: this.volumeElement?.nativeElement, id: `${engineId}-volume`, orientation: this.runtime.core.Enums.OrientationAxis.AXIAL }];
    if (definitions.some(item => !item.element)) throw new Error('Nie udało się przygotować obszaru rekonstrukcji.');
    engine.setViewports(definitions.map(item => ({
      element: item.element!, viewportId: item.id,
      type: layout === 'mpr' ? this.runtime!.core.Enums.ViewportType.ORTHOGRAPHIC : this.runtime!.core.Enums.ViewportType.VOLUME_3D,
      defaultOptions: { orientation: item.orientation, background: [0.025, 0.045, 0.05] },
    })));
    const volumeId = `cornerstoneStreamingImageVolume:${crypto.randomUUID()}`;
    const volume = await this.runtime.core.volumeLoader.createAndCacheVolume(volumeId, { imageIds: this.imageIds() });
    volume.load();
    await this.runtime.core.setVolumesForViewports(engine, [{ volumeId }], definitions.map(item => item.id));
    this.configureTools(definitions.map(item => item.id), layout);
    engine.render();
  }

  private configureTools(viewportIds: string[], layout: ViewerLayout): void {
    if (!this.runtime || !this.renderingEngine) return;
    const group = this.runtime.tools.ToolGroupManager.createToolGroup(this.toolGroupId);
    if (!group) throw new Error('Nie udało się uruchomić narzędzi diagnostycznych.');
    this.toolGroup = group;
    const names = [
      'WindowLevel', 'Pan', 'Zoom', 'StackScroll', 'Length', 'Angle', 'EllipticalROI',
      'RectangleROI', 'Probe', 'ArrowAnnotate', 'Magnify', 'PlanarRotate', 'Crosshairs', 'TrackballRotate',
    ];
    names.forEach(name => group.addTool(name));
    viewportIds.forEach(viewportId => group.addViewport(viewportId, this.renderingEngineId));
    group.setToolActive('Zoom', { bindings: [{ mouseButton: this.runtime.tools.Enums.MouseBindings.Secondary }] });
    group.setToolActive('Pan', { bindings: [{ mouseButton: this.runtime.tools.Enums.MouseBindings.Auxiliary }] });
    if (layout === 'stack') group.setToolActive('StackScroll', { bindings: [{ mouseButton: this.runtime.tools.Enums.MouseBindings.Wheel }] });
    if (layout === 'mpr') group.setToolEnabled('Crosshairs');
    if (layout === 'volume') {
      group.setToolActive('TrackballRotate', { bindings: [{ mouseButton: this.runtime.tools.Enums.MouseBindings.Primary }] });
      this.activeTool.set('TrackballRotate');
    } else {
      this.activeTool.set('WindowLevel');
      group.setToolActive('WindowLevel', { bindings: [{ mouseButton: this.runtime.tools.Enums.MouseBindings.Primary }] });
    }
  }

  private bindStackEvents(element: HTMLDivElement): void {
    if (!this.runtime) return;
    this.imageListener = ((event: CustomEvent<{ imageIdIndex?: number }>) => {
      const index = event.detail?.imageIdIndex ?? this.viewport?.getCurrentImageIdIndex();
      if (typeof index === 'number') this.frameIndex.set(index);
    }) as EventListener;
    this.annotationListener = ((event: CustomEvent<{ annotation?: Record<string, unknown> }>) => {
      if (event.detail?.annotation) void this.persistAnnotation(event.detail.annotation);
    }) as EventListener;
    element.addEventListener(this.runtime.core.EVENTS.STACK_NEW_IMAGE, this.imageListener);
    element.addEventListener(this.runtime.tools.Enums.Events.ANNOTATION_COMPLETED, this.annotationListener);
  }

  private applyReadableWindow(viewport: InstanceType<CoreModule['StackViewport']>): void {
    const image = viewport.getCornerstoneImage();
    if (!image) {
      this.windowLabel.set('Brak danych piksela');
      return;
    }
    if (image.color) {
      this.windowLabel.set('Obraz kolorowy');
      return;
    }
    const pixels = image.getPixelData();
    const step = Math.max(1, Math.floor(pixels.length / 25_000));
    const sample: number[] = [];
    for (let index = 0; index < pixels.length; index += step) {
      const value = Number(pixels[index]);
      if (Number.isFinite(value)) sample.push(value);
    }
    if (sample.length < 2) return;
    sample.sort((first, second) => first - second);
    const lowerStored = sample[Math.floor(sample.length * 0.01)] ?? image.minPixelValue;
    const upperStored = sample[Math.floor(sample.length * 0.99)] ?? image.maxPixelValue;
    const slope = Number.isFinite(image.slope) ? image.slope : 1;
    const intercept = Number.isFinite(image.intercept) ? image.intercept : 0;
    const lower = lowerStored * slope + intercept;
    const upper = upperStored * slope + intercept;
    this.windowLabel.set(`Zakres ${Math.round(lower)}–${Math.round(upper)}`);
    if (upper > lower) viewport.setProperties({ voiRange: { lower, upper } });
  }

  private async persistAnnotation(annotation: Record<string, unknown>): Promise<void> {
    const uid = typeof annotation['annotationUID'] === 'string' ? annotation['annotationUID'] : crypto.randomUUID();
    if (this.savedAnnotationUids.has(uid)) return;
    this.savedAnnotationUids.add(uid);
    const metadata = (annotation['metadata'] ?? {}) as Record<string, unknown>;
    const data = (annotation['data'] ?? {}) as Record<string, unknown>;
    const referencedImageId = typeof metadata['referencedImageId'] === 'string' ? metadata['referencedImageId'] : '';
    const sop = /\/instances\/([^/]+)/.exec(referencedImageId)?.[1] ?? null;
    const body: SaveImagingAnnotation = {
      seriesId: this.selectedSeries()?.backendId ?? null,
      sopInstanceUid: sop ? decodeURIComponent(sop) : null,
      frameOfReferenceUid: typeof metadata['FrameOfReferenceUID'] === 'string' ? metadata['FrameOfReferenceUID'] : null,
      type: typeof metadata['toolName'] === 'string' ? metadata['toolName'] : 'MEASUREMENT',
      geometry: this.serializable({ annotationUID: uid, metadata, handles: data['handles'] }),
      measurement: this.serializable({ cachedStats: data['cachedStats'], label: data['label'] }),
    };
    this.annotationStatus.set('Zapisujemy pomiar…');
    try {
      await firstValueFrom(this.api.createImagingAnnotation(this.workspace.role(), this.studyId(), body));
      this.annotationStatus.set('Pomiar zapisany w historii badania');
      setTimeout(() => this.annotationStatus.set(''), 3000);
    } catch {
      this.savedAnnotationUids.delete(uid);
      this.annotationStatus.set('Pomiar pozostał na obrazie, ale nie udało się zapisać go na serwerze.');
    }
  }

  private serializable(value: unknown): Record<string, unknown> {
    try {
      return JSON.parse(JSON.stringify(value, (_key, nested) => typeof nested === 'number' && !Number.isFinite(nested) ? null : nested)) as Record<string, unknown>;
    } catch {
      return {};
    }
  }

  private sessionRoot(session: ViewerSession): string {
    const path = new URL(session.dicomWebRoot || `/api/v1/viewer/${session.sessionId}/dicomweb`, window.location.origin).pathname;
    return `${window.location.origin}${path}`.replace(/\/$/, '');
  }

  private destroyViewer(): void {
    const stack = this.stackElement?.nativeElement;
    if (stack && this.runtime) {
      if (this.cinePlaying()) this.runtime.tools.utilities.cine.stopClip(stack, { stopDynamicCine: true });
      if (this.imageListener) stack.removeEventListener(this.runtime.core.EVENTS.STACK_NEW_IMAGE, this.imageListener);
      if (this.annotationListener) stack.removeEventListener(this.runtime.tools.Enums.Events.ANNOTATION_COMPLETED, this.annotationListener);
    }
    this.cinePlaying.set(false);
    this.imageListener = undefined;
    this.annotationListener = undefined;
    if (this.runtime && this.toolGroupId) this.runtime.tools.ToolGroupManager.destroyToolGroup(this.toolGroupId);
    this.toolGroup = undefined;
    this.renderingEngine?.destroy();
    this.renderingEngine = null;
    this.viewport = null;
    this.renderingEngineId = '';
    this.toolGroupId = '';
  }
}
