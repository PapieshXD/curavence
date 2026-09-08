import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { StudyRecord } from '../../../../core/models/medical';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AppIcon } from '../../../../shared/components/app-icon/app-icon';
import { StudyPreview } from '../../../../shared/components/study-preview/study-preview';

type DetailTab = 'summary' | 'images' | 'document';

interface StudyMonth {
  label: string;
  studies: StudyRecord[];
}

@Component({
  selector: 'app-patient-dashboard',
  imports: [AppIcon, StudyPreview],
  templateUrl: './patient-dashboard.html',
  styleUrl: './patient-dashboard.scss',
})
export class PatientDashboard {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly router = inject(Router, { optional: true });
  protected readonly selectedId = signal('');
  protected readonly activeTab = signal<DetailTab>('summary');
  protected readonly visibleCount = signal(8);

  protected readonly studies = computed(() => [...(this.workspace.data()?.studies ?? [])]
    .sort((left, right) => (right.performedAt ?? '').localeCompare(left.performedAt ?? '')));

  protected readonly months = computed<StudyMonth[]>(() => {
    const groups = new Map<string, StudyRecord[]>();
    for (const study of this.studies().slice(0, this.visibleCount())) {
      const key = study.performedAt?.slice(0, 7) || 'unknown';
      groups.set(key, [...(groups.get(key) ?? []), study]);
    }
    return Array.from(groups, ([key, studies]) => ({
      label: key === 'unknown' ? 'Bez daty' : this.monthLabel(`${key}-01`),
      studies,
    }));
  });
  protected readonly hasOlderEvents = computed(() => this.visibleCount() < this.studies().length);

  protected readonly selected = computed<StudyRecord | null>(() => {
    const studies = this.studies();
    return studies.find((study) => study.id === this.selectedId())
      ?? studies.find((study) => this.isImaging(study))
      ?? studies[0]
      ?? null;
  });

  protected selectStudy(study: StudyRecord): void {
    this.selectedId.set(study.id);
    this.activeTab.set('summary');
  }

  protected setTab(tab: DetailTab): void {
    this.activeTab.set(tab);
  }

  protected showOlderEvents(): void {
    this.visibleCount.update((count) => count + 8);
  }

  protected addMedicalData(): void {
    void this.router?.navigateByUrl('/documents/upload');
  }

  protected openSelected(): void {
    const study = this.selected();
    if (!study) return;
    if (study.imagingStudyId) {
      void this.router?.navigate(['/imaging-studies', study.imagingStudyId, 'viewer']);
      return;
    }
    void this.router?.navigate(['/documents', study.id]);
  }

  protected shareSelected(): void {
    this.workspace.selectView('sharing');
    this.workspace.notify('Wybierz odbiorcę i zakres danych do udostępnienia.');
  }

  protected downloadOriginal(study: StudyRecord): void {
    if (!study.primaryAssetId) {
      this.workspace.notify('Oryginalny plik nie jest jeszcze dostępny.');
      return;
    }
    this.workspace.download(study.primaryAssetId, study.originalFilename || `${this.consumerTitle(study)}.pdf`);
  }

  protected isImaging(study: StudyRecord): boolean {
    return ['MRI', 'CT', 'XRAY', 'ULTRASOUND'].includes(study.typeCode);
  }

  protected icon(study: StudyRecord): string {
    if (study.typeCode === 'LABORATORY') return 'lab';
    if (['EKG', 'EEG'].includes(study.typeCode)) return 'activity';
    if (this.isImaging(study)) return 'scan';
    return 'document';
  }

  protected consumerTitle(study: StudyRecord): string {
    if (study.typeCode === 'LABORATORY' && /kontrolne wyniki/i.test(study.title)) return 'Morfologia krwi';
    if (this.isImaging(study)) return study.typeName;
    return study.title
      .replace(/^\d+[_\s-]*/, '')
      .replace(/_/g, ' ')
      .replace(/^./, (letter) => letter.toLocaleUpperCase('pl'));
  }

  protected statusCopy(study: StudyRecord): string {
    if (study.ingestionStatus === 'FAILED') return 'Plik wymaga sprawdzenia';
    if (study.ingestionStatus !== 'READY') return 'Trwa przygotowanie badania';
    if (study.imagingStudyId) {
      return study.clinicalStatus === 'SIGNED' ? 'Obrazy i zatwierdzony opis' : 'Obrazy badania są dostępne';
    }
    return study.primaryAssetId ? 'Plik jest dostępny' : 'Zapis w historii — bez pliku';
  }

  protected explanation(study: StudyRecord): string {
    if (study.imagingStudyId) return 'Otwórz obrazy, aby zobaczyć całe badanie na jednej planszy. Możesz powiększyć dowolny obraz i włączyć dostępne oznaczenia analizy.';
    if (study.primaryAssetId) return 'Otwórz oryginalny plik, aby przeczytać wynik lub obejrzeć zapis badania. Zachowujemy dokument w formie otrzymanej z placówki.';
    return 'Ten wpis zawiera informacje o badaniu, ale nie ma jeszcze dołączonego pliku ani obrazów.';
  }

  protected reviewSummary(study: StudyRecord): string | null {
    return this.workspace.data()?.reviews.find((review) => review.studyId === study.id && review.status === 'SIGNED')?.conclusion || null;
  }

  protected openLabel(study: StudyRecord): string {
    return study.imagingStudyId ? 'Zobacz obrazy' : study.mimeType === 'application/pdf' ? 'Otwórz PDF' : study.primaryAssetId ? 'Otwórz plik' : 'Szczegóły wpisu';
  }

  protected openOriginal(): void {
    const study = this.selected();
    if (study) void this.router?.navigate(['/documents', study.id]);
  }

  protected dateDay(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { day: '2-digit' }).format(new Date(value)) : '—';
  }

  protected dateRest(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { month: 'short', year: 'numeric' }).format(new Date(value)) : '';
  }

  protected dateShort(value: string | null): string {
    return value ? new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'long', year: 'numeric' }).format(new Date(value)) : 'Brak daty';
  }

  private monthLabel(value: string): string {
    const label = new Intl.DateTimeFormat('pl-PL', { month: 'long', year: 'numeric' }).format(new Date(value));
    return label.charAt(0).toLocaleUpperCase('pl') + label.slice(1);
  }
}
