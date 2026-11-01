import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18n } from '../../../core/i18n/i18n.service';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent, BadgeKind } from '../../../ui/badge/badge.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { TableComponent } from '../../../ui/table/table.component';
import { AdminAuditApi } from './admin-audit.api';
import { AdminRow } from './admin-audit.model';

export interface AdminActorOption {
  id: string;
  label: string;
}

@Component({
  selector: 'app-admin-audit-page',
  standalone: true,
  imports: [
    FormsModule,
    TPipe,
    BadgeComponent,
    EmptyStateComponent,
    IconComponent,
    SkeletonComponent,
    TableComponent,
  ],
  templateUrl: './admin-audit.page.html',
  styleUrl: './admin-audit.page.css',
})
export class AdminAuditPage implements OnInit {
  private readonly api = inject(AdminAuditApi);
  private readonly i18n = inject(I18n);

  readonly rows = signal<AdminRow[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);

  readonly actorFilter = signal<string>('');
  readonly typeFilter = signal<string>('');
  readonly periodFilter = signal<string>('today');

  readonly actors = signal<AdminActorOption[]>([]);
  private readonly knownActors = new Map<string, string>();

  readonly columns = [
    { title: this.i18n.t('mcp.adminAudit.colWhen'), width: '130px' },
    { title: this.i18n.t('mcp.adminAudit.colWho'), width: '180px' },
    { title: this.i18n.t('mcp.adminAudit.colAction'), width: '180px' },
    { title: this.i18n.t('mcp.adminAudit.colObject'), width: '160px' },
    { title: this.i18n.t('mcp.adminAudit.colReasonOrDiff') },
  ];

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .list({
        actor: this.actorFilter() || undefined,
        type: this.typeFilter() || undefined,
        limit: 100,
      })
      .subscribe({
        next: (res) => {
          this.recordActors(res);
          this.rows.set(res);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err.message || 'Error');
          this.loading.set(false);
        },
      });
  }

  private recordActors(rows: AdminRow[]): void {
    let changed = false;
    for (const r of rows) {
      if (r.actorId && !this.knownActors.has(r.actorId)) {
        const label = r.actorOwner ? `${r.actorOwner} (${r.actorId})` : r.actorId;
        this.knownActors.set(r.actorId, label);
        changed = true;
      }
    }
    if (changed) {
      this.actors.set(
        Array.from(this.knownActors.entries()).map(([id, label]) => ({ id, label })),
      );
    }
  }

  onActorChange(val: string): void {
    this.actorFilter.set(val);
    this.loadData();
  }

  onTypeChange(val: string): void {
    this.typeFilter.set(val);
    this.loadData();
  }

  onPeriodChange(val: string): void {
    this.periodFilter.set(val);
    this.loadData();
  }

  resetFilters(): void {
    this.actorFilter.set('');
    this.typeFilter.set('');
    this.periodFilter.set('today');
    this.loadData();
  }

  formatDate(iso: string): string {
    const d = new Date(iso);
    const day = String(d.getDate()).padStart(2, '0');
    const mon = String(d.getMonth() + 1).padStart(2, '0');
    const h = String(d.getHours()).padStart(2, '0');
    const m = String(d.getMinutes()).padStart(2, '0');
    const s = String(d.getSeconds()).padStart(2, '0');
    return `${day}.${mon} ${h}:${m}:${s}`;
  }

  getActionBadgeKind(action: string): BadgeKind {
    if (action.startsWith('mode.')) return 'graphite';
    if (action.startsWith('tool.')) return 'info';
    if (action.startsWith('integration.')) return 'ok';
    if (action.startsWith('key.revoke')) return 'danger';
    return 'neutral';
  }

  getActionKey(action: string): string {
    const safe = action.replace('.', '_');
    return `mcp.adminAudit.action_${safe}`;
  }

  parseDiff(row: AdminRow): string {
    try {
      const afterObj = JSON.parse(row.after);
      if (afterObj.reason) {
        return afterObj.reason;
      }
      if (afterObj.mode) {
        return this.i18n.t('mcp.adminAudit.diffMode', { mode: afterObj.mode });
      }
      if (afterObj.enabled !== undefined) {
        return afterObj.enabled
          ? this.i18n.t('mcp.adminAudit.diffEnabled')
          : this.i18n.t('mcp.adminAudit.diffDisabled');
      }
      return row.after;
    } catch {
      return row.after || '—';
    }
  }
}
