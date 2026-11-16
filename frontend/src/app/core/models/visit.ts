export interface VisitAction {
  id: string; description: string; dueDate: string; responsible: string; completed: boolean;
  patientStatus: 'TODO' | 'DONE' | 'DIFFICULTY'; performedOn: string | null;
  patientNote: string; obstacle: string | null; reportedBy: string | null; reportedAt: string | null;
  reportedName: string | null; discussedName: string | null; discussedBy: string | null; discussedAt: string | null; revision: number; studyIds: string[];
}
export interface VisitProgress {
  status: string; performedOn: string | null; note: string; obstacle: string | null; studyIds: string[]; revision: number;
}
export interface VisitNotification { id: string; visitId: string; kind: string; createdAt: string; readAt: string | null; }
export interface Visit {
  id: string; patientId: string; patientName: string; reason: string; symptoms: string; questions: string;
  visitDate: string | null; grantId: string | null; accessStatus: string; recipientEmail: string | null;
  expiresAt: string | null; summary: string; approvedAt: string | null; approvedBy: string | null;
  createdAt: string; studyIds: string[]; actions: VisitAction[];
  newStudyIds: string[]; previousVisitId: string | null; includePreviousPlan: boolean;
  previousPlan: { id: string; summary: string; approvedAt: string; approvedBy: string; actions: VisitAction[] } | null;
}
