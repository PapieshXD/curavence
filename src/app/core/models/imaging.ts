export interface ImagingStudySummary {
  id: string;
  patientId: string;
  patientName: string;
  studyInstanceUid: string | null;
  modality: string | null;
  description: string;
  bodyPart: string | null;
  performedAt: string | null;
  seriesCount: number;
  instanceCount: number;
  storageProvider: 'ORTHANC' | 'GOOGLE_HEALTHCARE';
  sourceFormat: string | null;
  status: ImagingStatus;
  errorMessage: string | null;
}

export interface ImagingSeries {
  id: string;
  seriesInstanceUid: string;
  seriesNumber: number | null;
  description: string | null;
  sequenceName: string | null;
  imageCount: number;
  modality: string | null;
  rows: number | null;
  columns: number | null;
  pixelSpacingX: number | null;
  pixelSpacingY: number | null;
  sliceThickness: number | null;
  calculatedSpacing: number | null;
  stackEligible: boolean;
  mprEligible: boolean;
  volumeRenderingEligible: boolean;
  eligibilityReason: string | null;
}

export interface ImagingStudyDetails {
  study: ImagingStudySummary;
  series: ImagingSeries[];
}

export interface ImagingStudyStatus {
  studyId: string;
  status: ImagingStatus;
  progress: number;
  errorMessage: string | null;
}

export interface ImagingStudyCreated {
  id: string;
  status: ImagingStatus;
  progress: number;
}

export interface ViewerSession {
  sessionId: string;
  studyId: string;
  studyInstanceUid: string;
  expiresAt: string;
  viewerUrl: string;
}

export interface ImagingAnnotation {
  id: string;
  studyId: string;
  seriesId: string | null;
  sopInstanceUid: string | null;
  frameOfReferenceUid: string | null;
  authorId: string;
  type: string;
  geometry: Record<string, unknown>;
  measurement: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface SaveImagingAnnotation {
  seriesId: string | null;
  sopInstanceUid: string | null;
  frameOfReferenceUid: string | null;
  type: string;
  geometry: Record<string, unknown>;
  measurement: Record<string, unknown>;
  version?: number;
}

export type ImagingStatus =
  | 'UPLOADING'
  | 'VALIDATING'
  | 'STORING'
  | 'VERIFYING'
  | 'AVAILABLE'
  | 'FAILED'
  | 'CANCELLED';
