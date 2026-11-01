export interface CallRow {
  id: number;
  at: string;
  requestId: string;
  clientId: string;
  channel: string;
  tool: string;
  argsHash: string;
  outcome: string;
  errorCode: string;
  cacheHit: boolean;
  durationMs: number;
}

export interface CallFilterParams {
  from?: string;
  to?: string;
  client?: string;
  tool?: string;
  outcome?: string;
  before?: number;
  limit?: number;
}
