import { IntegrationView, LimitsChange, ToolView } from '../../features/mcp/integrations/integrations.model';
import { ModeChange, ModeView } from '../../features/mcp/mode/mode.model';
import { CallRow } from '../../features/mcp/audit/audit.model';
import { AdminRow } from '../../features/mcp/admin-audit/admin-audit.model';
import { INITIAL_ADMIN_AUDIT, INITIAL_CALLS, INITIAL_INTEGRATIONS, INITIAL_MODE } from './demo-fixtures-mcp';

export class DemoMcpStore {
  private catalog: IntegrationView[] = JSON.parse(JSON.stringify(INITIAL_INTEGRATIONS));
  private mode: ModeView = { ...INITIAL_MODE };
  private calls: CallRow[] = JSON.parse(JSON.stringify(INITIAL_CALLS));
  private adminRows: AdminRow[] = JSON.parse(JSON.stringify(INITIAL_ADMIN_AUDIT));
  private nextAdminId = 30;
  private nextCallId = 120;

  getCatalog(): IntegrationView[] {
    return JSON.parse(JSON.stringify(this.catalog));
  }

  updateIntegrationState(id: string, enabled: boolean, reason?: string, actorOwner = 'Демидов А. В.'): IntegrationView {
    const item = this.catalog.find((i) => i.integration.id === id);
    if (!item) throw new Error('integration_not_found');
    const before = { enabled: item.integration.enabled };
    item.integration.enabled = enabled;
    const after = { enabled, reason: reason ?? null };
    this.recordAdminAction('adm_7f3a', actorOwner, 'integration.state', 'integration', id, before, after);
    return JSON.parse(JSON.stringify(item));
  }

  updateToolState(name: string, enabled: boolean, reason?: string, actorOwner = 'Демидов А. В.'): ToolView {
    let found: ToolView | null = null;
    for (const item of this.catalog) {
      const t = item.tools.find((x) => x.name === name);
      if (t) {
        const before = { enabled: t.enabled };
        t.enabled = enabled;
        found = t;
        const after = { enabled, reason: reason ?? null };
        this.recordAdminAction('adm_7f3a', actorOwner, 'tool.state', 'tool', name, before, after);
        break;
      }
    }
    if (!found) throw new Error('tool_not_found');
    return JSON.parse(JSON.stringify(found));
  }

  updateToolLimits(name: string, body: LimitsChange, actorOwner = 'Демидов А. В.'): ToolView {
    let found: ToolView | null = null;
    for (const item of this.catalog) {
      const t = item.tools.find((x) => x.name === name);
      if (t) {
        const before = t.override;
        if (body.cacheTtlSeconds == null && body.timeoutMs == null && body.ratePerMinute == null) {
          t.override = null;
        } else {
          t.override = {
            cacheTtlMs: body.cacheTtlSeconds != null ? body.cacheTtlSeconds * 1000 : (t.override?.cacheTtlMs ?? t.cacheTtlSeconds * 1000),
            timeoutMs: body.timeoutMs != null ? body.timeoutMs : (t.override?.timeoutMs ?? t.timeoutMs),
            ratePerMinute: body.ratePerMinute != null ? body.ratePerMinute : (t.override?.ratePerMinute ?? t.ratePerMinute),
          };
          if (body.cacheTtlSeconds != null) t.cacheTtlSeconds = body.cacheTtlSeconds;
          if (body.timeoutMs != null) t.timeoutMs = body.timeoutMs;
          if (body.ratePerMinute != null) t.ratePerMinute = body.ratePerMinute;
        }
        found = t;
        this.recordAdminAction('adm_7f3a', actorOwner, 'tool.limits', 'tool', name, before, body);
        break;
      }
    }
    if (!found) throw new Error('tool_not_found');
    return JSON.parse(JSON.stringify(found));
  }

  getMode(): ModeView {
    return { ...this.mode };
  }

  changeMode(change: ModeChange, actorOwner = 'Демидов А. В.'): ModeView {
    const before = { ...this.mode };
    this.mode = {
      mode: change.mode,
      reason: change.reason,
      updatedAt: new Date().toISOString(),
    };
    this.recordAdminAction('adm_7f3a', actorOwner, 'mode.set', 'mode', 'mode', before, this.mode);
    return { ...this.mode };
  }

  getCalls(filters: { client?: string; tool?: string; outcome?: string; limit?: number; before?: number }): CallRow[] {
    // Generate occasional new call on poll
    if (Math.random() < 0.25) {
      this.generateLiveCall();
    }
    return this.calls
      .filter((c) => !filters.client || c.clientId.toLowerCase().includes(filters.client.toLowerCase()))
      .filter((c) => !filters.tool || c.tool === filters.tool)
      .filter((c) => !filters.outcome || c.outcome === filters.outcome)
      .filter((c) => !filters.before || c.id < filters.before)
      .slice(0, filters.limit || 50);
  }

  getAdminAudit(filters: { actor?: string; type?: string; object?: string; limit?: number; before?: number }): AdminRow[] {
    return this.adminRows
      .filter((a) => !filters.actor || a.actorId.includes(filters.actor) || a.actorOwner.includes(filters.actor))
      .filter((a) => !filters.type || a.objectType === filters.type)
      .filter((a) => !filters.object || a.objectId.includes(filters.object))
      .filter((a) => !filters.before || a.id < filters.before)
      .slice(0, filters.limit || 50);
  }

  private generateLiveCall(): void {
    const tools = ['rasp.day', 'docs_search', 'lk.profile', 'rasp.week', 'devdocs.search'];
    const clients = ['Сайт «Мой ТПУ»', 'Бот «Помоги мне»', 'Claude Code · деканат', 'Мобильное приложение'];
    const tool = tools[Math.floor(Math.random() * tools.length)];
    const client = clients[Math.floor(Math.random() * clients.length)];
    const isOk = Math.random() > 0.15;
    const newCall: CallRow = {
      id: this.nextCallId++,
      at: new Date().toISOString(),
      requestId: `req_${this.nextCallId}`,
      clientId: client,
      channel: 'mcp',
      tool,
      argsHash: Math.random().toString(16).substring(2, 6) + '…' + Math.random().toString(16).substring(2, 6),
      outcome: isOk ? 'ok' : 'failed',
      errorCode: isOk ? '' : 'таймаут сервиса',
      cacheHit: Math.random() > 0.5,
      durationMs: Math.floor(Math.random() * 800) + 10,
    };
    this.calls.unshift(newCall);
  }

  private recordAdminAction(actorId: string, actorOwner: string, action: string, type: string, obj: string, before: unknown, after: unknown): void {
    this.adminRows.unshift({
      id: this.nextAdminId++,
      at: new Date().toISOString(),
      actorId,
      actorOwner,
      action,
      objectType: type,
      objectId: obj,
      before: JSON.stringify(before),
      after: JSON.stringify(after),
    });
  }
}
