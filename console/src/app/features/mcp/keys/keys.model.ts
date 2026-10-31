export type KeyKind = 'person' | 'server' | 'demo';
export type GeoPolicy = 'ru_only' | 'any';

export interface KeyView {
  id: string;
  owner: string;
  keyPrefix: string;
  kind: KeyKind | string;
  scopes: string[];
  roles: string[];
  deniedTools: string[];
  rateLimits: Record<string, number>;
  readOnly: boolean;
  enabled: boolean;
  expiresAt: string;
  networks: string[];
  suspended: boolean;
  decoy: boolean;
  createdAt: string;
  lastUsedAt: string | null;
  calls30d: number;
  previousKeyUntil: string | null;
  geo: GeoPolicy | string;
  geoReason: string | null;
}

export interface ReissueRequest {
  graceHours?: number;
}

export interface IssuedKey {
  clientId: string;
  key: string;
  command: string;
}

export interface ResumeRequest {
  geo?: GeoPolicy | string;
  geoReason?: string;
}
