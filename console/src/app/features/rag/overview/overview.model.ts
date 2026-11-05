export interface RagIndexInfo {
  name: string;
  bytes: number;
}

export interface RagLastEval {
  at: string;
  hit3: number;
  hit5: number;
  falseFound: number;
  questions: number;
}

export interface RagOverviewData {
  sources: number;
  documents: number;
  documentsOff: number;
  chunks: number;
  embeddings: number;
  lastIndexed: string | null;
  misses7d: number;
  modelVersion: string;
  index: RagIndexInfo | null;
  lastEval: RagLastEval | null;
}

export interface RagOverviewSource {
  id: number;
  kind: string;
  title: string;
  address: string;
  enabled: boolean;
  documents: number;
  chunks: number;
  fileSize: number | null;
  lastJob: string | null;
  createdAt: string;
}

export interface RagOverviewJob {
  id: number;
  sourceId: number;
  kind: string;
  status: string;
  title?: string;
  chunks: number | null;
  createdAt: string;
}

export interface RagOverviewMiss {
  query: string;
  count: number;
  examples: string[];
  lastAt: string;
}
