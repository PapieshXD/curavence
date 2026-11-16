export interface LibraryItem {
  id: string; title: string; performedAt: string | null; typeCode: string; typeName: string;
  sourceName: string | null; originalFilename: string | null; hasExtractedText: boolean;
  indexStatus: string; snippet: string; archivedAt: string | null; metadataRevision: number;
  primaryAssetId: string | null; imagingStudyId: string | null; ingestionStatus: string;
}
export interface LibraryPage {items: LibraryItem[]; total: number; page: number; pageSize: number;}
export interface RestorePreview {revision: number; readers: {grantId: string;name: string;email: string;scope: string}[];}
export interface MetadataHistory {author:string;createdAt:string;oldTitle:string;newTitle:string;oldPerformedAt:string|null;newPerformedAt:string|null;oldTypeCode:string;newTypeCode:string;}
export interface ExportJob {id:string;status:string;errorMessage:string|null;expiresAt:string;format:'PDF'|'ZIP';}
