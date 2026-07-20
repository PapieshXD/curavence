export interface SessionData {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  displayName: string;
  roles: string[];
  availableViews: WorkspaceView[];
  superuser: boolean;
  effectiveView: WorkspaceView;
}

export type WorkspaceView = 'admin' | 'patient' | 'doctor' | 'therapy';

export interface OverviewData {
  patients: number;
  studies: number;
  activeGrants: number;
  auditEvents: number;
  awaitingReview: number;
}

export interface PatientProfile {
  patientId: string;
  userId: string;
  firstName: string;
  lastName: string;
  email: string;
  dateOfBirth: string | null;
  biologicalSex: string | null;
  phone: string | null;
}

export interface PatientRecord extends Omit<PatientProfile, 'patientId'> {
  id: string;
  studyCount: number;
}

export interface StudyRecord {
  id: string;
  patientId: string;
  patientName: string;
  typeCode: string;
  typeName: string;
  title: string;
  description: string | null;
  sourceName: string | null;
  performedAt: string | null;
  ingestionStatus: string;
  clinicalStatus: string;
  primaryAssetId: string | null;
  originalFilename: string | null;
  mimeType: string | null;
  sizeBytes: number | null;
}

export interface AccessGrantRecord {
  id: string;
  patientId: string;
  patientName: string;
  recipientUserId: string;
  recipientName: string;
  recipientEmail: string;
  recipientRole: string;
  scope: string;
  purpose: string | null;
  createdAt: string;
  expiresAt: string | null;
  status: string;
}

export interface AuditRecord {
  id: string;
  actorName: string;
  patientId: string | null;
  patientName: string | null;
  resourceType: string;
  resourceId: string | null;
  action: string;
  success: boolean;
  denialReason: string | null;
  createdAt: string;
}

export interface ReviewRecord {
  id: string;
  studyId: string;
  studyTitle: string;
  reviewerName: string;
  status: string;
  summary: string | null;
  conclusion: string | null;
  updatedAt: string;
  signedAt: string | null;
}

export interface StudyTypeOption { code: string; name: string }
export interface RecipientOption { id: string; name: string; email: string; role: string }

export interface WorkspaceData {
  session: SessionData;
  overview: OverviewData;
  profile: PatientProfile | null;
  patients: PatientRecord[];
  studies: StudyRecord[];
  grants: AccessGrantRecord[];
  auditEvents: AuditRecord[];
  reviews: ReviewRecord[];
  studyTypes: StudyTypeOption[];
  recipients: RecipientOption[];
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  refreshExpiresIn: number;
}
