export interface MedicalTerm {
  id: string; name: string; aliases: string[]; category: string; contextualAliases: string[];
  definition: string; sources: { title: string; url: string }[];
  reviewStatus: string; reviewedAt: string | null; reviewedBy: string | null;
}
export interface ReadingBlock {
  id: string; text: string; occurrences: { start: number; end: number; termId: string }[];
}
export interface ReadingGuideData {
  enabled: boolean; documentId: string; sourceVersion: string; dictionaryVersion: string;
  blocks: ReadingBlock[]; terms: MedicalTerm[];
}
export interface AssistantItem {
  id: string; text: string; deadlineText: string | null; source: { blockId: string; quote: string };
}
export interface AssistantSummary {
  sourceVersion: string; generatorVersion: string; model: string; contextTruncated: boolean;
  analyzedCharacters: number; totalCharacters: number;
  points: AssistantItem[]; recommendations: AssistantItem[]; questions: AssistantItem[];
}
export interface PersonalReminder {
  id: string; documentId: string; recommendationId: string; description: string;
  sourceQuote: string; dueDate: string; completed: boolean;
}
