export interface ChunkItem {
  ord: number;
  heading: string;
  text: string;
  tokens: number;
}

export interface SectionItem {
  id: string;
  ord: number;
  path: string;
  text: string;
  tokens: number;
  chunks: ChunkItem[];
}

export interface DocumentDetail {
  id: number;
  sourceId: number;
  title: string;
  url: string;
  access: string;
  status: string;
  sections: number;
  chunks: number;
  updatedAt: string;
  indexedAt: string;
  redactions?: number;
}

export interface DocumentViewResponse {
  document: DocumentDetail;
  sections: SectionItem[];
}
