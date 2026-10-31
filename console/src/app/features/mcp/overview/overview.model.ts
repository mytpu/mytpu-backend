export interface ScopeInfo {
  name: string;
  title: string;
  description: string;
}

export interface HealthStatus {
  status: 'UP' | 'DOWN' | 'DEGRADED' | string;
  message?: string;
}

export interface IntegrationStatus {
  id: string;
  title: string;
  scopes: ScopeInfo[];
  enabled: boolean;
  health: HealthStatus;
  missingSecrets: string[];
  tools: string[];
}

export interface Totals {
  calls: number;
  errors: number;
  cacheHits: number;
}

export interface ToolStats {
  tool: string;
  calls: number;
  errors: number;
  cacheHits: number;
  avgMs: number;
  p95Ms: number | null;
}

export interface ClientStats {
  clientId: string;
  owner: string;
  calls: number;
  errors: number;
}

export interface StatsOverview {
  from: string;
  to: string;
  totals: Totals;
  tools: ToolStats[];
  topClients: ClientStats[];
}

export interface OverviewView {
  mode: string;
  integrations: IntegrationStatus[];
  stats: StatsOverview;
}
