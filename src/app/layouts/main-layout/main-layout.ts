import { Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { WorkspaceRole } from '../../core/models/workspace';
import { Auth } from '../../core/services/auth';
import { WorkspaceStore } from '../../core/services/workspace-store';
import { AdminDashboard } from '../../features/admin/pages/admin-dashboard/admin-dashboard';
import { AccessHistory } from '../../features/audit/pages/access-history/access-history';
import { ConsentList } from '../../features/consents/pages/consent-list/consent-list';
import { DoctorDashboard } from '../../features/doctor/pages/doctor-dashboard/doctor-dashboard';
import { PatientDetails } from '../../features/doctor/pages/patient-details/patient-details';
import { DocumentList } from '../../features/documents/pages/document-list/document-list';
import { PatientDashboard } from '../../features/patient/pages/patient-dashboard/patient-dashboard';
import { PatientProfile } from '../../features/patient/pages/patient-profile/patient-profile';
import { PatientTimeline } from '../../features/patient/pages/patient-timeline/patient-timeline';
import { TherapyDashboard } from '../../features/therapy/pages/therapy-dashboard/therapy-dashboard';
import { AppIcon } from '../../shared/components/app-icon/app-icon';

@Component({
  selector: 'app-main-layout',
  imports: [FormsModule, AppIcon, AdminDashboard, AccessHistory, ConsentList, DoctorDashboard,
    PatientDetails, DocumentList, PatientDashboard, PatientProfile, PatientTimeline, TherapyDashboard],
  templateUrl: './main-layout.html',
  styleUrl: './main-layout.scss',
})
export class MainLayout implements OnInit {
  protected readonly workspace = inject(WorkspaceStore);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router, { optional: true });

  ngOnInit(): void { this.workspace.initialize(); }

  protected changeRole(event: Event): void {
    this.workspace.selectRole((event.target as HTMLSelectElement).value as WorkspaceRole);
  }

  protected updateSearch(value: string): void { this.workspace.search.set(value); }
  protected showNotifications(): void { this.workspace.notify('Nie masz nowych pilnych powiadomień.'); }

  protected roleLabel(role: WorkspaceRole): string {
    return { admin: 'Administrator', patient: 'Pacjent', doctor: 'Lekarz / diagnosta', therapy: 'Fizjo / trener' }[role];
  }

  protected logout(): void {
    this.auth.logout();
    this.router?.navigateByUrl('/login');
  }
}
