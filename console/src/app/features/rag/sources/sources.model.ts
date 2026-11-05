export interface SourceItem {
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

export interface JobItem {
  id: number;
  sourceId: number;
  kind: string;
  status: string;
  log: string;
  redactions: Record<string, number>;
  chunks: number | null;
  createdBy: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  title?: string;
}

export interface SourceCreateResult {
  sourceId: number;
  jobId: number;
}
