export interface AdminRow {
  id: number;
  at: string;
  actorId: string;
  actorOwner: string;
  action: string;
  objectType: string;
  objectId: string;
  before: string | null;
  after: string | null;
}

export interface AdminFilterParams {
  from?: string;
  to?: string;
  actor?: string;
  type?: string;
  object?: string;
  before?: number;
  limit?: number;
}
