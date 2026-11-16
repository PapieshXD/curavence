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

export type ClinicalResultInterpretation = 'LOW' | 'NORMAL' | 'HIGH' | 'ABNORMAL' | 'UNKNOWN';

export interface ClinicalResultReferenceRange {
  low: number | null;
  high: number | null;
  label?: string | null;
}

export interface ClinicalResultHistoryPoint {
  observedAt: string;
  value: number;
  unit?: string | null;
  method?: string | null;
  parameterCode?: string | null;
  parameterName?: string | null;
  referenceRange?: ClinicalResultReferenceRange | null;
}

export interface ClinicalResultParameter {
  id: string;
  code?: string | null;
  name: string;
  value: number | string | null;
  numericValue?: number | null;
  unit?: string | null;
  interpretation?: ClinicalResultInterpretation | null;
  referenceRange?: ClinicalResultReferenceRange | null;
  observedAt?: string | null;
  history?: ClinicalResultHistoryPoint[];
  method?: string | null;
  description?: string | null;
  sourceExcerpt?: string | null;
  sourceKind?: 'laboratory' | 'ocr' | 'manual' | 'doctor_approved' | 'document' | null;
  confidence?: number | null;
  referenceSource?: 'document' | 'catalog' | null;
}

export interface ClinicalResultSection {
  id: string;
  title: string;
  content: string;
}

/**
 * Generic payload for any structured diagnostic result. Categories are deliberately
 * open strings so new laboratory domains can be rendered without changing the UI.
 */
export interface ClinicalResultSet {
  name?: string | null;
  category?: string | null;
  categoryLabel?: string | null;
  presentation?: 'parameters' | 'report' | string | null;
  material?: string | null;
  comment?: string | null;
  parameters: ClinicalResultParameter[];
  sections?: ClinicalResultSection[];
}

export interface StudyRecord {
  metadataRevision?:number;
  id: string;
  /** Present only when this record has a real DICOM/NIfTI study. */
  imagingStudyId?: string | null;
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
  hasExtractedText: boolean;
  documentProcessingStatus: DocumentProcessingStatus | null;
  resultSet?: ClinicalResultSet | null;
}

export type DocumentProcessingStatus = 'TEXT_EXTRACTED' | 'OCR_REQUIRED' | 'ORIGINAL_STORED';

export interface DocumentUploadResult {
  duplicateIds?:string[];
  id: string;
  status: 'READY';
  processingStatus: DocumentProcessingStatus;
  pages: number;
  extractedCharacters: number;
  textTruncated: boolean;
}

export interface DocumentConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface DocumentQuestionResponse {
  answer: string;
  model: string;
  contextTruncated: boolean;
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
  summaryHtml?: string | null;
  conclusionHtml?: string | null;
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
