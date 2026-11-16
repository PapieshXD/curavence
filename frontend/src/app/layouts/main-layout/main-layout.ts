import { VisitNotifications } from '../../features/visits/visit-notifications';
import { Visits } from '../../features/visits/visits';
import { Component, DestroyRef, computed, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { WorkspaceRole } from '../../core/models/workspace';
import { Auth } from '../../core/services/auth';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { AdminDashboard } from '../../features/admin/pages/admin-dashboard/admin-dashboard';
import { AccessHistory } from '../../features/audit/pages/access-history/access-history';
import { ConsentList } from '../../features/consents/pages/consent-list/consent-list';
import { DoctorDashboard } from '../../features/doctor/pages/doctor-dashboard/doctor-dashboard';
import { PatientDetails } from '../../features/doctor/pages/patient-details/patient-details';
import { DocumentList } from '../../features/documents/pages/document-list/document-list';
import { DocumentUpload } from '../../features/documents/pages/document-upload/document-upload';
import { DocumentDetails } from '../../features/documents/pages/document-details/document-details';
import { PatientDashboard } from '../../features/patient/pages/patient-dashboard/patient-dashboard';
import { PatientProfile } from '../../features/patient/pages/patient-profile/patient-profile';
import { PatientResults } from '../../features/patient/pages/patient-results/patient-results';
import { PatientTimeline } from '../../features/patient/pages/patient-timeline/patient-timeline';
import { TherapyDashboard } from '../../features/therapy/pages/therapy-dashboard/therapy-dashboard';
import { MriStudyList } from '../../features/imaging/pages/mri-study-list/mri-study-list';
import { MriUpload } from '../../features/imaging/pages/mri-upload/mri-upload';
import { MriStudyDetailsPage } from '../../features/imaging/pages/mri-study-details/mri-study-details';
import { MriViewer } from '../../features/imaging/pages/mri-viewer/mri-viewer';
import { AppIcon } from '../../shared/components/app-icon/app-icon';

@Component({
  selector: 'app-main-layout',
  imports: [VisitNotifications, Visits, FormsModule, AppIcon, AdminDashboard, AccessHistory, ConsentList, DoctorDashboard,
    PatientDetails, DocumentList, DocumentUpload, DocumentDetails, PatientDashboard, PatientProfile, PatientResults, PatientTimeline, TherapyDashboard,
    MriStudyList, MriUpload, MriStudyDetailsPage, MriViewer],
  templateUrl: './main-layout.html',
  styleUrl: './main-layout.scss',
})
export class MainLayout implements OnInit {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router, { optional: true });
  private readonly destroyRef = inject(DestroyRef);
  protected readonly routePath = signal('/');
  protected readonly documentId = computed(() =>
    this.routePath().match(/^\/documents\/([^/]+)$/)?.[1] ?? '',
  );

  ngOnInit(): void {
    this.workspace.initialize();
    this.updateRoute(this.router?.url ?? '/');
    this.router?.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((event) => this.updateRoute(event.urlAfterRedirects));
  }

  protected changeRole(event: Event): void {
    this.workspace.selectRole((event.target as HTMLSelectElement).value as WorkspaceRole);
  }

  protected updateSearch(value: string): void { this.workspace.search.set(value); }
  protected openProfile(): void { this.selectNavigation('profile'); }

  protected roleLabel(role: WorkspaceRole): string {
    return { admin: 'Administrator', patient: 'Pacjent', doctor: 'Lekarz / diagnosta', therapy: 'Fizjo / trener' }[role];
  }

  protected selectNavigation(view: string): void {
    if (view === 'imaging') {
      const patientId = this.workspace.data()?.profile?.patientId ?? this.workspace.data()?.patients?.[0]?.id;
      if (!patientId) {
        this.workspace.notify('Brak pacjenta z dostępnymi badaniami obrazowymi.');
        return;
      }
      this.workspace.selectView('imaging');
      void this.router?.navigate(['/patients', patientId, 'imaging']);
      return;
    }
    const select = () => this.workspace.selectView(view);
    if (this.routePath() !== '/') void this.router?.navigateByUrl('/').then(select);
    else select();
  }

  protected isNavigationActive(view: string): boolean {
    if (this.workspace.role() === 'patient' && this.routePath() !== '/') {
      return view === (this.routePath() === '/documents/upload' ? 'results' : this.workspace.studyReturnView());
    }
    return view === 'imaging'
      ? this.isImagingRoute()
      : !this.isImagingRoute() && this.workspace.activeView() === view;
  }

  protected logout(): void {
    this.auth.logout();
    this.router?.navigateByUrl('/login');
  }

  private updateRoute(url: string): void {
    const path = url.split('?', 1)[0] || '/';
    this.routePath.set(path);
    if (this.workspace.role() === 'patient' && path !== '/') {
      this.workspace.activeView.set('results');
      return;
    }
    if (this.isImagingRoute(path)) this.workspace.activeView.set('imaging');
    else if (path === '/documents/upload' || /^\/documents\/[^/]+$/.test(path)) {
      this.workspace.activeView.set('documents');
    }
  }

  private isImagingRoute(path = this.routePath()): boolean {
    return /\/patients\/[^/]+\/(?:mri|imaging)(?:\/upload)?$/.test(path)
      || /\/imaging-studies\/[^/]+(?:\/viewer)?$/.test(path);
  }
}

