export interface DocumentRow {
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

export interface DocumentFilter {
  q?: string;
  source?: number;
  status?: string;
  limit?: number;
}
