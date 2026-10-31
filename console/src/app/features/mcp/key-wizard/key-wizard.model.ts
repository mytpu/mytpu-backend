import { I18n } from '../../../core/i18n/i18n.service';
import { IntegrationStatus } from '../overview/overview.model';

export interface ToolCatalogView {
  name: string;
  scope: string;
  kind: string;
  sensitivity: string;
  description: string;
  enabled: boolean;
  cacheTtlSeconds: number;
  timeoutMs: number;
  ratePerMinute: number;
}

export interface IntegrationCatalogView {
  integration: IntegrationStatus;
  tools: ToolCatalogView[];
}

export interface NewKeyBody {
  owner: string;
  scopes: string[];
  roles?: string[];
  deniedTools?: string[];
  rateLimits?: Record<string, number>;
  write?: boolean;
  days?: number;
  kind?: string;
  networks?: string[];
  decoy?: boolean;
  geo?: string;
  geoReason?: string;
}

export interface PermissionsChangeBody {
  scopes: string[];
  deniedTools?: string[];
  rateLimits?: Record<string, number>;
  readOnly?: boolean;
  networks?: string[];
  geo?: string;
  geoReason?: string;
}

export function validateCidr(network: string): { valid: boolean; error?: string } {
  const trimmed = network.trim();
  if (!trimmed) return { valid: false, error: 'network_empty' };
  const slash = trimmed.indexOf('/');
  const ip = slash < 0 ? trimmed : trimmed.substring(0, slash);
  const pStr = slash < 0 ? null : trimmed.substring(slash + 1);

  const v4 = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    for (let i = 1; i <= 4; i++) {
      const n = Number(v4[i]);
      if (n < 0 || n > 255) return { valid: false, error: 'network_invalid' };
    }
    const prefix = pStr !== null ? Number(pStr) : 32;
    if (isNaN(prefix) || prefix < 0 || prefix > 32) return { valid: false, error: 'network_invalid' };
    if (prefix < 8) return { valid: false, error: 'network_too_wide' };
    return { valid: true };
  }

  if (ip.includes(':')) {
    const prefix = pStr !== null ? Number(pStr) : 128;
    if (isNaN(prefix) || prefix < 0 || prefix > 128) return { valid: false, error: 'network_invalid' };
    if (prefix < 32) return { valid: false, error: 'network_too_wide' };
    return { valid: true };
  }

  return { valid: false, error: 'network_invalid' };
}

export function validateWizardStep1(
  val: { owner: string; kind: string; days: number; networks: string; ruOnly: boolean; geoReason: string },
  maxDays: number,
  i18n: I18n,
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!val.owner.trim()) {
    errors['owner'] = i18n.t('mcp.wizard.errorOwnerRequired');
  }
  if (val.days < 1 || val.days > maxDays) {
    errors['days'] = i18n.t('mcp.wizard.errorDaysRange', { max: maxDays });
  }
  const nets = val.networks.split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
  if (val.kind === 'server' && nets.length === 0) {
    errors['networks'] = i18n.t('mcp.wizard.errorNetworksRequired');
  }
  for (const net of nets) {
    const res = validateCidr(net);
    if (!res.valid) {
      errors['networks'] = res.error === 'network_too_wide'
        ? i18n.t('mcp.wizard.errorNetworkTooWide', { network: net })
        : i18n.t('mcp.wizard.errorNetworkInvalid', { network: net });
      break;
    }
  }
  if (!val.ruOnly) {
    if (!val.geoReason.trim()) {
      errors['geoReason'] = i18n.t('mcp.wizard.errorGeoReasonRequired');
    } else if (val.geoReason.length > 200) {
      errors['geoReason'] = i18n.t('mcp.wizard.errorGeoReasonLength');
    }
  }
  return errors;
}
