import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { ClinicalResultSet, StudyRecord } from '../models/medical';
import { WorkspaceRole } from '../models/workspace';
import { Api } from './api';
import { WorkspaceStore } from './workspace-store';

/**
 * Resolves extracted diagnostic PDFs once and shares their structured payload
 * between the Documents and Results screens.
 */
@Injectable({ providedIn: 'root' })
export class ClinicalResultsStore {
  private readonly api = inject(Api);
  private readonly workspace = inject(WorkspaceStore);
  private readonly results = signal<ReadonlyMap<string, ClinicalResultSet | null>>(new Map());
  private readonly pendingKeys = signal<ReadonlySet<string>>(new Set());
  private readonly resolvedKeys = signal<ReadonlySet<string>>(new Set());
  private generation = 0;
  private identity = '';

  constructor() {
    effect(() => {
      const data = this.workspace.data();
      const role = this.workspace.role();
      const identity = `${data?.session.id ?? ''}:${role}:${this.workspace.realtimeRevision()}`;
      if (this.identity !== identity) {
        this.identity = identity;
        this.generation++;
        this.results.set(new Map());
        this.pendingKeys.set(new Set());
        this.resolvedKeys.set(new Set());
      }
      if (!data) return;

      for (const study of data.studies) {
        if (study.resultSet) {
          this.storeResult(study.id, study.resultSet);
          continue;
        }
        if (!this.canResolve(study)) continue;

        const key = `${role}:${study.id}`;
        const shouldResolve = untracked(
          () => !this.pendingKeys().has(key) && !this.resolvedKeys().has(key),
        );
        if (shouldResolve) this.resolve(key, study.id, role);
      }
    });
  }

  resultSetFor(studyId: string): ClinicalResultSet | null {
    return this.results().get(studyId) ?? null;
  }

  isClinicalResult(study: StudyRecord): boolean {
    return Boolean(study.resultSet || this.resultSetFor(study.id));
  }

  isPending(study: StudyRecord): boolean {
    if (!this.canResolve(study) || study.resultSet) return false;
    const key = `${this.workspace.role()}:${study.id}`;
    return !this.resolvedKeys().has(key);
  }

  private canResolve(study: StudyRecord): boolean {
    return study.hasExtractedText && study.ingestionStatus !== 'FAILED';
  }

  private resolve(key: string, studyId: string, role: WorkspaceRole): void {
    const generation = this.generation;
    this.pendingKeys.update((current) => new Set(current).add(key));
    this.api.documentClinicalResults(role, studyId).subscribe({
      next: (result) => { if (generation === this.generation) this.storeResult(studyId, result); },
      error: () => {
        if (generation !== this.generation) return;
        this.storeResult(studyId, null);
        this.markResolved(key);
      },
      complete: () => { if (generation === this.generation) this.markResolved(key); },
    });
  }

  private storeResult(studyId: string, result: ClinicalResultSet | null): void {
    this.results.update((current) => {
      const next = new Map(current);
      next.set(studyId, result);
      return next;
    });
  }

  private markResolved(key: string): void {
    this.pendingKeys.update((current) => {
      const next = new Set(current);
      next.delete(key);
      return next;
    });
    this.resolvedKeys.update((current) => new Set(current).add(key));
  }
}
