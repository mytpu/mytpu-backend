import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { I18n } from '../../../core/i18n/i18n.service';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent, BadgeKind } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../ui/select/select.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { TableComponent } from '../../../ui/table/table.component';
import { AdminAuditApi } from './admin-audit.api';
import { actionKind, actionLabel, AuditSummary, objectLabel, summarize } from './admin-audit.describe';
import { AuditDetails, details } from './admin-audit.fields';
import { AdminRow } from './admin-audit.model';

type Period = 'today' | '24h' | '7d' | '30d';

/** Строка журнала, подготовленная для показа: итог и подписи считаются один раз при загрузке */
interface AuditView {
  row: AdminRow;
  date: string;
  time: string;
  action: string;
  kind: BadgeKind;
  object: string;
  summary: AuditSummary;
  details: AuditDetails;
}

const PAGE = 100;
const OBJECT_TYPES = ['mode', 'tool', 'integration', 'key', 'key_request', 'connector', 'rag'];
const PERIOD_HOURS: Record<Exclude<Period, 'today'>, number> = { '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };

@Component({
  selector: 'app-admin-audit-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    IconComponent,
    SelectComponent,
    SkeletonComponent,
    TableComponent,
  ],
  templateUrl: './admin-audit.page.html',
  styleUrl: './admin-audit.page.css',
})
export class AdminAuditPage implements OnInit {
  private readonly api = inject(AdminAuditApi);
  private readonly i18n = inject(I18n);
  private readonly t = this.i18n.t.bind(this.i18n);

  readonly rows = signal<AuditView[]>([]);
  readonly loading = signal<boolean>(true);
  readonly loadingMore = signal<boolean>(false);
  readonly hasMore = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly expanded = signal<ReadonlySet<number>>(new Set());

  readonly actorFilter = signal<string>('');
  readonly typeFilter = signal<string>('');
  readonly periodFilter = signal<Period>('today');

  private readonly knownActors = signal<ReadonlyMap<string, string>>(new Map());

  readonly periodOptions: SelectOption<Period>[] = [
    { value: 'today', label: this.t('mcp.adminAudit.filterPeriodToday') },
    { value: '24h', label: this.t('mcp.adminAudit.filterPeriod24h') },
    { value: '7d', label: this.t('mcp.adminAudit.filterPeriod7d') },
    { value: '30d', label: this.t('mcp.adminAudit.filterPeriod30d') },
  ];
  readonly typeOptions: SelectOption[] = [
    { value: '', label: this.t('mcp.adminAudit.filterActionAll') },
    ...OBJECT_TYPES.map((type) => ({ value: type, label: this.t(`mcp.adminAudit.objects.${type}`) })),
  ];
  readonly actorOptions = computed<SelectOption[]>(() => [
    { value: '', label: this.t('mcp.adminAudit.filterActorAll') },
    ...Array.from(this.knownActors(), ([id, owner]) => ({ value: id, label: owner || id, hint: owner ? id : undefined })),
  ]);

  readonly columns = [
    { title: this.t('mcp.adminAudit.colWhen'), width: '108px' },
    { title: this.t('mcp.adminAudit.colWho'), width: '170px' },
    { title: this.t('mcp.adminAudit.colAction'), width: '200px' },
    { title: this.t('mcp.adminAudit.colChange') },
    { title: '', width: '120px' },
  ];

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading.set(true);
    this.error.set(null);
    this.expanded.set(new Set());
    this.fetch(undefined, (views) => this.rows.set(views), () => this.loading.set(false));
  }

  loadMore(): void {
    const last = this.rows().at(-1);
    if (!last || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.fetch(last.row.id, (views) => this.rows.update((rows) => [...rows, ...views]), () => this.loadingMore.set(false));
  }

  private fetch(before: number | undefined, apply: (views: AuditView[]) => void, done: () => void): void {
    this.api
      .list({
        from: this.periodStart().toISOString(),
        actor: this.actorFilter() || undefined,
        type: this.typeFilter() || undefined,
        before,
        limit: PAGE,
      })
      .subscribe({
        next: (res) => {
          this.recordActors(res);
          apply(res.map((row) => this.view(row)));
          this.hasMore.set(res.length === PAGE);
          done();
        },
        error: (err) => {
          this.error.set(err.message || 'Error');
          done();
        },
      });
  }

  private periodStart(): Date {
    const period = this.periodFilter();
    if (period === 'today') {
      const d = new Date();
      d.setHours(0, 0, 0, 0);
      return d;
    }
    return new Date(Date.now() - PERIOD_HOURS[period] * 3600_000);
  }

  private view(row: AdminRow): AuditView {
    const d = new Date(row.at);
    const two = (n: number) => String(n).padStart(2, '0');
    const summary = summarize(row, this.t);
    return {
      row,
      date: `${two(d.getDate())}.${two(d.getMonth() + 1)}`,
      time: `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`,
      action: actionLabel(row.action, this.t),
      kind: actionKind(row, summary.failed),
      object: objectLabel(row, this.t),
      summary,
      details: details(row),
    };
  }

  private recordActors(rows: AdminRow[]): void {
    const known = this.knownActors();
    const fresh = rows.filter((r) => r.actorId && !known.has(r.actorId));
    if (fresh.length === 0) return;
    const next = new Map(known);
    for (const r of fresh) next.set(r.actorId, r.actorOwner);
    this.knownActors.set(next);
  }

  toggle(id: number): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  onActorChange(val: string): void {
    this.actorFilter.set(val);
    this.loadData();
  }

  onTypeChange(val: string): void {
    this.typeFilter.set(val);
    this.loadData();
  }

  onPeriodChange(val: Period): void {
    this.periodFilter.set(val);
    this.loadData();
  }

  resetFilters(): void {
    this.actorFilter.set('');
    this.typeFilter.set('');
    this.periodFilter.set('today');
    this.loadData();
  }
}
