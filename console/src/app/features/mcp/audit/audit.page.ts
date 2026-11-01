import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { AuditApi } from './audit.api';
import { CallRow } from './audit.model';
import { AuditFiltersComponent, PeriodFilter, SegmentFilter } from './audit-filters.component';
import { AuditTableComponent } from './audit-table.component';

@Component({
  selector: 'app-audit-page',
  standalone: true,
  imports: [
    TPipe, ButtonComponent, EmptyStateComponent,
    SkeletonComponent, AuditFiltersComponent, AuditTableComponent,
  ],
  templateUrl: './audit.page.html',
  styleUrl: './audit.page.css',
})
export class AuditPage implements OnInit {
  private readonly api = inject(AuditApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly calls = signal<CallRow[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);

  readonly period = signal<PeriodFilter>('today');
  readonly clientFilter = signal<string>('');
  readonly toolFilter = signal<string>('');
  readonly segment = signal<SegmentFilter>('all');
  readonly liveWatch = signal<boolean>(true);
  readonly isScrollPaused = signal<boolean>(false);

  readonly highlightedIds = signal<Set<number>>(new Set());
  private knownIds = new Set<number>();
  readonly availableClients = signal<string[]>([]);
  readonly availableTools = signal<string[]>([]);
  private readonly knownClients = new Set<string>();
  private readonly knownTools = new Set<string>();

  readonly filteredCalls = computed(() => {
    let list = this.calls();
    const seg = this.segment();
    if (seg === 'errors') {
      list = list.filter((c) => c.outcome === 'failed' || c.outcome === 'denied');
    } else if (seg === 'slow') {
      list = list.filter((c) => c.durationMs > 500);
    }
    return list;
  });

  readonly isErrorsEmpty = computed(() => {
    return this.segment() === 'errors' && this.filteredCalls().length === 0;
  });

  ngOnInit(): void {
    this.loadCalls();
    const timer = setInterval(() => {
      if (this.liveWatch() && !this.isScrollPaused()) {
        this.pollCalls();
      }
    }, 5000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  loadCalls(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .getCalls({
        client: this.clientFilter() || undefined,
        tool: this.toolFilter() || undefined,
        limit: 100,
      })
      .subscribe({
        next: (rows) => {
          this.knownIds = new Set(rows.map((r) => r.id));
          this.recordClients(rows);
          this.calls.set(rows);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err.message || 'Error');
          this.loading.set(false);
        },
      });
  }

  private pollCalls(): void {
    this.api
      .getCalls({
        client: this.clientFilter() || undefined,
        tool: this.toolFilter() || undefined,
        limit: 100,
      })
      .subscribe({
        next: (rows) => {
          const freshIds: number[] = [];
          for (const r of rows) {
            if (!this.knownIds.has(r.id)) {
              freshIds.push(r.id);
              this.knownIds.add(r.id);
            }
          }
          if (freshIds.length > 0) {
            this.highlightedIds.set(new Set(freshIds));
            setTimeout(() => this.highlightedIds.set(new Set()), 1000);
          }
          this.recordClients(rows);
          this.calls.set(rows);
        },
      });
  }

  /** Списки фильтров — из загруженных строк журнала, без заранее зашитых значений. */
  private recordClients(rows: CallRow[]): void {
    const add = (set: Set<string>, v: string | null | undefined) => (v && !set.has(v) ? (set.add(v), true) : false);
    let clients = false;
    let tools = false;
    for (const r of rows) {
      clients = add(this.knownClients, r.clientId) || clients;
      tools = add(this.knownTools, r.tool) || tools;
    }
    if (clients) this.availableClients.set(Array.from(this.knownClients).sort());
    if (tools) this.availableTools.set(Array.from(this.knownTools).sort());
  }

  onPeriodChange(p: PeriodFilter): void { this.period.set(p); this.loadCalls(); }
  onClientChange(c: string): void { this.clientFilter.set(c); this.loadCalls(); }
  onToolChange(t: string): void { this.toolFilter.set(t); this.loadCalls(); }
  onSegmentChange(s: SegmentFilter): void { this.segment.set(s); }
  onLiveWatchChange(v: boolean): void { this.liveWatch.set(v); }
  onScrollPaused(paused: boolean): void { this.isScrollPaused.set(paused); }

  resetAllFilters(): void {
    this.clientFilter.set('');
    this.toolFilter.set('');
    this.segment.set('all');
    this.loadCalls();
  }

  exportCsv(): void {
    const outcome = this.segment() === 'errors' ? 'errors' : undefined;
    this.api
      .downloadCsv({
        client: this.clientFilter() || undefined,
        tool: this.toolFilter() || undefined,
        outcome,
      })
      .subscribe({
        next: (csvText) => this.downloadBlob(csvText),
        error: () => this.exportCsvClientFallback(),
      });
  }

  private formatCsvField(val: unknown): string {
    if (val === null || val === undefined) return '""';
    let s = String(val);
    if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
    return `"${s.replace(/"/g, '""')}"`;
  }

  private exportCsvClientFallback(): void {
    const list = this.filteredCalls();
    const header = 'id;at;request_id;client_id;channel;tool;args_hash;outcome;error_code;cache_hit;duration_ms\n';
    const lines = list
      .map((c) => [
        c.id, this.formatCsvField(c.at), this.formatCsvField(c.requestId),
        this.formatCsvField(c.clientId), this.formatCsvField(c.channel),
        this.formatCsvField(c.tool), this.formatCsvField(c.argsHash),
        this.formatCsvField(c.outcome), this.formatCsvField(c.errorCode),
        c.cacheHit, c.durationMs,
      ].join(';'))
      .join('\n');
    this.downloadBlob('\uFEFF' + header + lines);
  }

  private downloadBlob(content: string): void {
    const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `gateway-audit-${new Date().toISOString().substring(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}
