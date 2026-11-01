import { Component, input, output } from '@angular/core';
import { CdkVirtualScrollViewport, CdkFixedSizeVirtualScroll, CdkVirtualForOf } from '@angular/cdk/scrolling';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { CallRow } from './audit.model';

@Component({
  selector: 'app-audit-table',
  standalone: true,
  imports: [
    CdkVirtualScrollViewport,
    CdkFixedSizeVirtualScroll,
    CdkVirtualForOf,
    TPipe,
    BadgeComponent,
  ],
  template: `
    <div class="table-container">
      <div class="table-header-row">
        <div class="th col-time">{{ 'mcp.audit.colTime' | t }}</div>
        <div class="th col-client">{{ 'mcp.audit.colClient' | t }}</div>
        <div class="th col-tool">{{ 'mcp.audit.colTool' | t }}</div>
        <div class="th col-outcome">{{ 'mcp.audit.colOutcome' | t }}</div>
        <div class="th col-duration">{{ 'mcp.audit.colDuration' | t }}</div>
        <div class="th col-cache">{{ 'mcp.audit.colCache' | t }}</div>
        <div class="th col-args">{{ 'mcp.audit.colArgsHash' | t }}</div>
      </div>

      <cdk-virtual-scroll-viewport
        itemSize="48"
        class="virtual-viewport"
        (scrolledIndexChange)="onScrolledIndex($event)"
      >
        <div
          *cdkVirtualFor="let row of rows(); trackBy: trackById"
          [class]="'table-data-row' + (isHighlighted(row.id) ? ' is-new' : '')"
        >
          <div class="td col-time">{{ formatTime(row.at) }}</div>
          <div class="td col-client">{{ row.clientId }}</div>
          <div class="td col-tool">
            <span class="tool-tag">{{ row.tool }}</span>
          </div>
          <div class="td col-outcome">
            @if (row.outcome === 'ok') {
              <app-badge kind="ok">{{ 'mcp.audit.outcomeOk' | t }}</app-badge>
            } @else if (row.outcome === 'failed') {
              <app-badge kind="danger">{{ row.errorCode || 'failed' }}</app-badge>
            } @else if (row.outcome === 'denied') {
              <app-badge kind="warn">{{ row.errorCode || 'denied' }}</app-badge>
            } @else {
              <app-badge kind="info">{{ row.outcome }}</app-badge>
            }
          </div>
          <div class="td col-duration">
            {{ 'mcp.audit.durationMs' | t: { ms: row.durationMs } }}
          </div>
          <div class="td col-cache">
            {{ row.cacheHit ? ('mcp.audit.cacheYes' | t) : row.durationMs <= 2 ? ('mcp.audit.cacheNone' | t) : ('mcp.audit.cacheNo' | t) }}
          </div>
          <div class="td col-args">
            <span class="hash-tag">{{ row.argsHash }}</span>
          </div>
        </div>
      </cdk-virtual-scroll-viewport>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .table-container {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      overflow-x: auto;
      width: 100%;
    }
    .table-header-row {
      display: flex;
      align-items: center;
      min-width: 900px;
      padding: 10px 16px;
      border-bottom: 1px solid var(--line);
      background: var(--bg-nested);
      font-size: 12px;
      font-weight: 700;
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .virtual-viewport {
      height: 480px;
      min-width: 900px;
    }
    .table-data-row {
      display: flex;
      align-items: center;
      min-width: 900px;
      height: 48px;
      padding: 0 16px;
      border-bottom: 1px solid var(--line);
      font-size: 13px;
      color: var(--text-primary);
      box-sizing: border-box;
      transition: background-color var(--dur-base) var(--ease);
    }
    .table-data-row:hover {
      background: var(--bg-nested);
    }
    .table-data-row.is-new {
      background: var(--green-soft);
    }
    .col-time { width: 84px; flex-shrink: 0; color: var(--text-secondary); }
    .col-client { flex: 1; min-width: 150px; font-weight: 500; }
    .col-tool { width: 140px; flex-shrink: 0; }
    .col-outcome { width: 220px; flex-shrink: 0; }
    .col-duration { width: 116px; flex-shrink: 0; color: var(--text-secondary); }
    .col-cache { width: 54px; flex-shrink: 0; color: var(--text-muted); }
    .col-args { width: 110px; flex-shrink: 0; }
    .tool-tag {
      font-family: var(--font-mono);
      font-size: 12px;
      background: var(--bg-chip);
      padding: 2px 6px;
      border-radius: 4px;
      color: var(--text-primary);
    }
    .hash-tag {
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--text-muted);
    }
  `,
})
export class AuditTableComponent {
  readonly rows = input.required<CallRow[]>();
  readonly highlightedIds = input<Set<number>>(new Set());

  readonly scrollPaused = output<boolean>();

  trackById(_index: number, row: CallRow): number {
    return row.id;
  }

  isHighlighted(id: number): boolean {
    return this.highlightedIds().has(id);
  }

  onScrolledIndex(index: number): void {
    this.scrollPaused.emit(index > 0);
  }

  formatTime(iso: string): string {
    const d = new Date(iso);
    const h = String(d.getHours()).padStart(2, '0');
    const m = String(d.getMinutes()).padStart(2, '0');
    const s = String(d.getSeconds()).padStart(2, '0');
    return `${h}:${m}:${s}`;
  }
}
