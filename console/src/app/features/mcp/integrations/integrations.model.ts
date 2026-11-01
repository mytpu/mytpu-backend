export interface ToolLimitsOverride {
  cacheTtlMs?: number;
  timeoutMs?: number;
  ratePerMinute?: number;
}

export interface ToolView {
  name: string;
  scope: string;
  kind: string;
  sensitivity: string;
  description: string;
  enabled: boolean;
  cacheTtlSeconds: number;
  timeoutMs: number;
  ratePerMinute: number;
  override: ToolLimitsOverride | null;
}

export interface ScopeInfo {
  name: string;
  title: string;
  description?: string;
}

export interface IntegrationStatus {
  id: string;
  title: string;
  scopes: ScopeInfo[];
  enabled: boolean;
  health: { status: string };
  missingSecrets: string[];
  tools: string[];
}

export interface IntegrationView {
  integration: IntegrationStatus;
  tools: ToolView[];
}

export interface StateChange {
  enabled: boolean;
  reason?: string;
}

export interface LimitsChange {
  cacheTtlSeconds: number | null;
  timeoutMs: number | null;
  ratePerMinute: number | null;
}
