export interface ConnectorSummary {
  id: string;
  title: string;
  publishedVersion: number | null;
  latestVersion: number;
  updatedAt: string;
}

export interface ConnectorListItem {
  connector: ConnectorSummary;
  secretsSet: string[];
  secretsNeeded: string[];
}

export type ConnectorAuthType = 'none' | 'header' | 'query' | 'basic' | 'oauth2';

export interface ConnectorAuth {
  type: ConnectorAuthType;
  name?: string | null;
  tokenUrl?: string | null;
  oauthScope?: string | null;
}

export interface ConnectorScope {
  name: string;
  title: string;
  description?: string | null;
}

export type ConnectorParamIn = 'path' | 'query';
export type ConnectorParamType = 'string' | 'integer' | 'number' | 'boolean';

export interface ConnectorParam {
  name: string;
  in: ConnectorParamIn;
  type: ConnectorParamType;
  required?: boolean;
  default?: unknown;
  description?: string;
  enum?: string[];
}

export interface ConnectorField {
  name: string;
  pointer: string;
  label: string;
}

export type ConnectorResultMode = 'object' | 'list' | 'values';

export interface ConnectorResult {
  pointer: string;
  mode: ConnectorResultMode;
  fields: ConnectorField[];
  maxItems?: number;
}

export interface ConnectorToolSpec {
  name: string;
  description: string;
  path: string;
  params: ConnectorParam[];
  result: ConnectorResult;
  summary: string;
  cacheTtlSeconds?: number;
  timeoutMs?: number;
  ratePerMinute?: number;
}

export interface ConnectorSpec {
  title: string;
  baseUrl: string;
  auth: ConnectorAuth;
  scope: ConnectorScope;
  tools: ConnectorToolSpec[];
}

export interface ConnectorVersion {
  version: number;
  createdAt: string;
  createdBy: string;
  published: boolean;
  spec: ConnectorSpec;
}

export interface ConnectorDetails {
  id: string;
  publishedVersion: number | null;
  secretsSet: string[];
  versions: ConnectorVersion[];
}

export interface PublishResult {
  id: string;
  publishedVersion: number;
}

export interface SaveVersionResult {
  id: string;
  version: number;
}

export interface ConnectorInvalidError {
  error: string;
  message?: string;
  problems?: string[];
}

export interface UnpublishResult {
  id: string;
  published: boolean;
}

export interface ConnectorTestRequest {
  tool: string;
  arguments: Record<string, unknown>;
}

export interface ConnectorTestResult {
  ok: boolean;
  status?: number;
  raw?: string;
  rawTruncated?: boolean;
  summary?: string;
  data?: unknown;
  error?: string;
  message?: string;
  problems?: string[];
}

export interface SetSecretResult {
  id: string;
  secret: string;
  set: boolean;
}

