import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { ConsentList } from './consent-list';
import { SharingApi, SharingRequest } from '../../services/sharing-api';
import { WorkspaceStore } from '../../../../core/services/workspace-store';
import { AccessGrantRecord, WorkspaceData } from '../../../../core/models/medical';

const grant = (id: string, status: string): AccessGrantRecord => ({
  id, patientId: 'patient-a', patientName: 'Anna Kowalska', recipientUserId: 'doctor-a',
  recipientName: 'Piotr Wójcik', recipientEmail: 'lekarz@example.test', recipientRole: 'DOCTOR',
  scope: 'FULL_STUDY', purpose: 'Konsultacja', createdAt: '2026-08-05T10:00:00Z',
  expiresAt: '2026-12-01T10:00:00Z', status,
});

const request = (id: string, status: SharingRequest['status'], grantId: string | null): SharingRequest => ({
  id, status, grantId, requesterName: 'Piotr Wójcik', requesterEmail: 'lekarz@example.test',
  requesterRole: 'DOCTOR', patientId: 'patient-a', patientName: 'Anna Kowalska',
  scope: 'IMAGING_STUDIES', purpose: 'Opis badania', durationDays: 14,
  createdAt: '2026-08-04T10:00:00Z', expiresAt: '2026-08-06T10:00:00Z',
  studies: [{ id: 'study-a', title: 'Badanie MR', typeName: 'Rezonans magnetyczny' }],
  serverNow: '2026-09-30T10:00:00Z',
});

describe('ConsentList sharing history', () => {
  const workspace = {
    role: signal('patient'),
    data: signal({ grants: [grant('legacy-active', 'ACTIVE'), grant('legacy-revoked', 'REVOKED')] } as WorkspaceData),
    loading: signal(false),
    load: vi.fn(),
  };
  const sharing = { requests: vi.fn(() => of([] as SharingRequest[])) };

  beforeEach(() => {
    workspace.role.set('patient');
    workspace.data.set({ grants: [grant('legacy-active', 'ACTIVE'), grant('legacy-revoked', 'REVOKED')] } as WorkspaceData);
    workspace.load.mockClear();
    sharing.requests.mockClear();
    sharing.requests.mockReturnValue(of([]));
    TestBed.configureTestingModule({
      imports: [ConsentList],
      providers: [{ provide: WorkspaceStore, useValue: workspace }, { provide: SharingApi, useValue: sharing }],
    });
  });

  async function render() {
    const fixture = TestBed.createComponent(ConsentList);
    await fixture.whenStable();
    return fixture;
  }

  it('shows existing active and revoked grants even when there are no code requests', async () => {
    const fixture = await render();
    const history = fixture.nativeElement.querySelector('.history-section');
    expect(history.textContent).toContain('Udostępnienia 2');
    expect(history.textContent).toContain('Dostęp aktywny');
    expect(history.textContent).toContain('Dostęp cofnięty');
    expect(history.textContent).toContain('Piotr Wójcik');
    expect(workspace.load).toHaveBeenCalledOnce();
  });

  it('does not duplicate a grant linked to an approved request and keeps rejected requests', async () => {
    sharing.requests.mockReturnValue(of([request('approved', 'APPROVED', 'legacy-active'), request('rejected', 'REJECTED', null)]));
    const fixture = await render();
    const history = fixture.nativeElement.querySelector('.history-section');
    expect(history.querySelectorAll('.history-list > div')).toHaveLength(3);
    expect(history.textContent).toContain('Odrzucona');
    expect(history.textContent).toContain('Udostępnione badania: 1');
  });

  it('marks elapsed active grants as expired and refreshes both data sources', async () => {
    workspace.data.set({ grants: [{ ...grant('old', 'ACTIVE'), expiresAt: '2020-01-01T00:00:00Z' }] } as WorkspaceData);
    const fixture = await render();
    expect(fixture.nativeElement.querySelector('.history-section').textContent).toContain('Wygasła');
    (fixture.componentInstance as any).refresh();
    expect(workspace.load).toHaveBeenCalledTimes(2);
    expect(sharing.requests).toHaveBeenCalledTimes(2);
  });
});
