export type RequestStatus = 'new' | 'approved' | 'rejected';
export type KeyKind = 'person' | 'server' | 'demo';

export interface KeyRequestView {
  id: string;
  at: string;
  requester: string;
  contact: string;
  purpose: string;
  scopes: string[];
  kind: KeyKind | string;
  days: number;
  networks: string[];
  status: RequestStatus;
  decidedBy: string | null;
  decidedAt: string | null;
  rejectReason: string | null;
  clientId: string | null;
}

export interface CreateKeyRequestBody {
  requester: string;
  contact: string;
  purpose: string;
  scopes: string[];
  kind: string;
  days?: number;
  networks?: string[];
}

export interface ApproveBody {
  owner?: string;
  scopes?: string[];
  deniedTools?: string[];
  rateLimits?: Record<string, number>;
  write?: boolean;
  days?: number;
  kind?: string;
  networks?: string[];
  geo?: string;
  geoReason?: string;
}

export interface RejectBody {
  reason: string;
}
