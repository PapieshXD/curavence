import { Component, ElementRef, OnDestroy, OnInit, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../../../core/services/api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { ImagingAnnotation, ImagingStudyDetails, SaveImagingAnnotation } from '../../../../core/models/imaging';

@Component({
  selector: 'app-mri-viewer',
  imports: [AppIcon, FormsModule],
  templateUrl: './mri-viewer.html',
  styleUrl: './mri-viewer.scss',
})
export class MriViewer implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  private readonly workspace = inject(WorkspaceStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);

  @ViewChild('viewerFrame') private viewerFrame?: ElementRef<HTMLDivElement>;
  protected readonly studyId = this.route.snapshot.paramMap.get('studyId') ?? '';
  protected readonly viewerUrl = signal<SafeResourceUrl | null>(null);
  protected readonly loading = signal(true);
  protected readonly frameLoaded = signal(false);
  protected readonly error = signal('');
  protected readonly expiresAt = signal<string | null>(null);
  protected readonly details = signal<ImagingStudyDetails | null>(null);
  protected readonly annotations = signal<ImagingAnnotation[]>([]);
  protected readonly selectedSeriesId = signal<string | null>(null);
  protected readonly note = signal('');
  protected readonly savingNote = signal(false);
  protected readonly annotationError = signal('');
  private viewerOrigin = '';
  private readonly messageListener = (event: MessageEvent) => this.handleViewerMessage(event);

  ngOnInit(): void {
    window.addEventListener('message', this.messageListener);
    this.loadContext();
    this.openSession();
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.messageListener);
  }

  protected openSession(): void {
    this.loading.set(true);
    this.frameLoaded.set(false);
    this.error.set('');
    this.api.createViewerSession(this.workspace.role(), this.studyId).subscribe({
      next: (session) => {
        this.expiresAt.set(session.expiresAt);
        this.viewerOrigin = new URL(session.viewerUrl, window.location.origin).origin;
        this.viewerUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(session.viewerUrl));
      },
      error: (error) => {
        this.loading.set(false);
        this.error.set(error?.error?.message ?? 'Nie udało się uruchomić viewera obrazowego.');
      },
    });
  }

  protected loaded(): void {
    this.loading.set(false);
    this.frameLoaded.set(true);
  }

  protected back(): void {
    void this.router.navigate(['/imaging-studies', this.studyId]);
  }

  protected fullscreen(): void {
    void this.viewerFrame?.nativeElement.requestFullscreen();
  }

  protected saveNote(): void {
    const text = this.note().trim();
    if (!text) return;
    this.savingNote.set(true);
    this.annotationError.set('');
    const body: SaveImagingAnnotation = {
      seriesId: this.selectedSeriesId(),
      sopInstanceUid: null,
      frameOfReferenceUid: null,
      type: 'NOTE',
      geometry: { kind: 'study-note' },
      measurement: { text },
    };
    this.api.createImagingAnnotation(this.workspace.role(), this.studyId, body).subscribe({
      next: (created) => {
        this.annotations.update((items) => [...items, created]);
        this.note.set('');
        this.savingNote.set(false);
      },
      error: (error) => {
        this.savingNote.set(false);
        this.annotationError.set(error?.error?.message ?? 'Nie udało się zapisać adnotacji.');
      },
    });
  }

  protected deleteAnnotation(annotation: ImagingAnnotation): void {
    this.api.deleteImagingAnnotation(this.workspace.role(), this.studyId, annotation.id).subscribe({
      next: () => this.annotations.update((items) => items.filter((item) => item.id !== annotation.id)),
      error: (error) => this.annotationError.set(error?.error?.message ?? 'Nie udało się usunąć adnotacji.'),
    });
  }

  protected annotationText(annotation: ImagingAnnotation): string {
    const value = annotation.measurement['text'];
    return typeof value === 'string' ? value : annotation.type;
  }

  protected annotationDate(value: string): string {
    return new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value));
  }

  private loadContext(): void {
    this.api.imagingStudy(this.workspace.role(), this.studyId).subscribe({
      next: (details) => {
        this.details.set(details);
        this.selectedSeriesId.set(details.series[0]?.id ?? null);
      },
    });
    this.api.imagingAnnotations(this.workspace.role(), this.studyId).subscribe({
      next: (annotations) => this.annotations.set(annotations),
      error: (error) => this.annotationError.set(error?.error?.message ?? 'Nie udało się pobrać adnotacji.'),
    });
  }

  private handleViewerMessage(event: MessageEvent): void {
    if (!this.viewerOrigin || event.origin !== this.viewerOrigin || event.source !== window.frames[0]) return;
    if (event.data?.type !== 'mednode:annotation' || typeof event.data.payload !== 'object') return;
    const payload = event.data.payload as SaveImagingAnnotation;
    this.api.createImagingAnnotation(this.workspace.role(), this.studyId, payload).subscribe({
      next: (created) => {
        this.annotations.update((items) => [...items, created]);
        (event.source as WindowProxy | null)?.postMessage(
          { type: 'mednode:annotation-saved', payload: created }, event.origin,
        );
      },
    });
  }
}
