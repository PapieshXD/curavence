import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { Auth } from './auth';
import { Api } from './api';
import { TokenStorage } from './token-storage';
import { WorkspaceStore } from './workspace-store';

describe('Auth', () => {
  let service: Auth;
  let tokens: TokenStorageStub;
  let workspace: WorkspaceStoreStub;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: Api, useClass: ApiStub },
        { provide: TokenStorage, useClass: TokenStorageStub },
        { provide: WorkspaceStore, useClass: WorkspaceStoreStub },
      ],
    });
    service = TestBed.inject(Auth);
    tokens = TestBed.inject(TokenStorage) as unknown as TokenStorageStub;
    workspace = TestBed.inject(WorkspaceStore) as unknown as WorkspaceStoreStub;
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('clears the previous user context before storing a new login token', () => {
    service.login('pacjent@med-node.test', 'Test123!').subscribe();

    expect(tokens.clearCount).toBe(1);
    expect(workspace.resetCount).toBe(1);
    expect(tokens.token).toBe('patient-token');
  });

  it('clears both the token and cached workspace on logout', () => {
    service.logout();

    expect(tokens.clearCount).toBe(1);
    expect(workspace.resetCount).toBe(1);
  });
});

class ApiStub {
  login() {
    return of({ accessToken: 'patient-token', refreshToken: '', expiresIn: 300, refreshExpiresIn: 0 });
  }
}

class TokenStorageStub {
  token = 'doctor-token';
  clearCount = 0;

  set(token: string): void {
    this.token = token;
  }

  clear(): void {
    this.clearCount += 1;
    this.token = '';
  }

  isAuthenticated(): boolean {
    return this.token.length > 0;
  }
}

class WorkspaceStoreStub {
  resetCount = 0;

  reset(): void {
    this.resetCount += 1;
  }
}
