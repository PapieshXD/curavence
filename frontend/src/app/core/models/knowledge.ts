export interface KnowledgeSource { id:string; kind:'DOCUMENT'|'VISIT'; targetId:string; title:string; version:string; limitation:string; }
export interface KnowledgePoint { kind:string; sourceId:string; quote:string; }
export interface KnowledgePage { id:string; title:string; points:KnowledgePoint[]; }
export interface KnowledgeView { id:string; status:string; updatedAt:string; error:string|null; sources:KnowledgeSource[]; pages:KnowledgePage[]; }
export interface KnowledgeReply { points:KnowledgePoint[]; message:string; model:string; contextLimited:boolean; }
