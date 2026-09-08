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

export interface ImagingFramePreview {
  id: string;
  index: number;
  sopInstanceUid: string;
  frameNumber: number;
  rows: number;
  columns: number;
  imageUrl: string;
  overlayUrl: string | null;
}

export interface ImagingSeriesOverview {
  id: string;
  seriesInstanceUid: string;
  description: string;
  modality: string;
  frameCount: number;
  frames: ImagingFramePreview[];
  contactSheetUrl: string | null;
  overlayContactSheetUrl: string | null;
  contactSheetColumns: number;
  contactSheetRows: number;
  cellWidth: number;
  cellHeight: number;
  overlayStatus: 'AVAILABLE' | 'NOT_ANALYZED' | 'UNAVAILABLE' | 'ALIGNMENT_UNAVAILABLE';
  overlayMessage: string;
  legend: { label: string; color: string }[];
  warnings: string[];
}

export interface ImagingOverview {
  studyId: string;
  selectedSeriesId: string;
  previewUrl: string;
  series: ImagingSeriesOverview[];
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
  dicomWebRoot: string;
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

export type ImagingAnalysisStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'DEGRADED'
  | 'UNSUPPORTED'
  | 'FAILED'
  | 'CANCELLED';

export interface ImagingAnalysisClassification {
  modality?: string;
  anatomy?: string;
  pipeline?: string;
  confidence?: number;
  source?: 'DICOM_METADATA' | 'IMAGE_FALLBACK' | 'UNKNOWN';
  selectedSeriesInstanceUid?: string | null;
  selectedSeriesInstanceUids?: string[];
}

export interface ImagingAnalysisQuality {
  status?: 'acceptable' | 'requires_review' | 'not_assessed';
  seriesCount?: number;
  assessedSlices?: number;
  totalSlices?: number;
  coverage?: number | null;
  noise?: number | null;
  motion?: number | null;
  warnings?: string[];
  method?: string;
}

export interface ImagingMeasurement {
  region: string;
  volumeMl?: number | null;
  meanIntensity?: number | null;
  intensityUnit?: string | null;
  pairedRegion?: string | null;
  asymmetry?: number | null;
  status?: string;
}

export interface ImagingObservation {
  type: string;
  region?: string | null;
  confidence?: number | null;
  status?: string;
  evidence?: Record<string, unknown>;
}

export interface ImagingCandidateFinding {
  id?: string;
  type: string;
  region?: string | null;
  confidence?: number | null;
  volumeMl?: number | null;
  sliceIndex?: number | null;
  artifactId?: string | null;
  status?: string;
}

export interface ImagingAnalysisArtifact {
  id: string;
  kind: string;
  label: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  sliceIndex: number | null;
  seriesId: string | null;
}

export interface ImagingAnalysis {
  analysisVersion?: number;
  parentAnalysisId?: string | null;
  scope?: {selectionMode: string; includedSeries?: string[]; excludedSeries?: string[]};
  seriesResults?: {seriesInstanceUid: string; analysisStatus: string; role: string}[];
  evidenceHash?: string;
  audience?: 'PATIENT' | 'PHYSIOTHERAPY' | 'CLINICIAN';
  explanation?: {
    headline?: string; whatWasSeen?: string; whatItMayMean?: string; whatItDoesNotMean?: string;
    nextStep?: string; questionsForDoctor?: string[]; clinicalContext?: string; functionalRelevance?: string;
    precautions?: string[]; recommendedQuestions?: string[]; limitations?: string[]; technicalSummary?: string;
  };
  id: string;
  studyId: string;
  pipeline: string;
  engine: string | null;
  status: ImagingAnalysisStatus;
  progress: number;
  classification: ImagingAnalysisClassification;
  quality: ImagingAnalysisQuality;
  measurements: ImagingMeasurement[];
  observations: ImagingObservation[];
  candidateFindings: ImagingCandidateFinding[];
  modelVersions: Record<string, string>;
  warnings: string[];
  summary: string | null;
  disclaimer: string;
  errorMessage: string | null;
  artifacts: ImagingAnalysisArtifact[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface ImagingAnalysisCreated {
  id: string;
  studyId: string;
  status: ImagingAnalysisStatus;
  progress: number;
}
