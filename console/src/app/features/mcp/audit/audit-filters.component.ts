import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { IconComponent } from '../../../ui/icon/icon.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';

export type SegmentFilter = 'all' | 'errors' | 'slow';
export type PeriodFilter = 'today' | '24h' | '7d' | '30d';

@Component({
  selector: 'app-audit-filters',
  standalone: true,
  imports: [FormsModule, TPipe, IconComponent, ToggleComponent],
  template: `
    <div class="filters-bar">
      <div class="filter-item">
        <app-icon name="calendar" [size]="16" class="select-icon" />
        <select
          class="custom-select"
          [ngModel]="period()"
          (ngModelChange)="periodChange.emit($event)"
        >
          <option value="today">{{ 'mcp.audit.filterPeriodToday' | t }}</option>
          <option value="24h">{{ 'mcp.audit.filterPeriod24h' | t }}</option>
          <option value="7d">{{ 'mcp.audit.filterPeriod7d' | t }}</option>
          <option value="30d">{{ 'mcp.audit.filterPeriod30d' | t }}</option>
        </select>
      </div>

      <div class="filter-item">
        <select
          class="custom-select"
          [ngModel]="clientFilter()"
          (ngModelChange)="clientChange.emit($event)"
        >
          <option value="">{{ 'mcp.audit.filterClientAll' | t }}</option>
          @for (c of clients(); track c) {
            <option [value]="c">{{ c }}</option>
          }
        </select>
      </div>

      <div class="filter-item">
        <select
          class="custom-select"
          [ngModel]="toolFilter()"
          (ngModelChange)="toolChange.emit($event)"
        >
          <option value="">{{ 'mcp.audit.filterToolAll' | t }}</option>
          @for (tool of tools(); track tool) {
            <option [value]="tool">{{ tool }}</option>
          }
        </select>
      </div>

      <div class="segmented-control">
        <button
          type="button"
          [class]="'seg-btn' + (segment() === 'all' ? ' is-active' : '')"
          (click)="segmentChange.emit('all')"
        >
          {{ 'mcp.audit.segAll' | t }}
        </button>
        <button
          type="button"
          [class]="'seg-btn' + (segment() === 'errors' ? ' is-active' : '')"
          (click)="segmentChange.emit('errors')"
        >
          {{ 'mcp.audit.segErrors' | t }}
        </button>
        <button
          type="button"
          [class]="'seg-btn' + (segment() === 'slow' ? ' is-active' : '')"
          (click)="segmentChange.emit('slow')"
        >
          {{ 'mcp.audit.segSlow' | t }}
        </button>
      </div>

      <div class="spacer"></div>

      <div class="live-toggle">
        <app-toggle [checked]="liveWatch()" (changed)="liveWatchChange.emit($event)" />
        <span class="live-label">{{ 'mcp.audit.liveWatch' | t }}</span>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .filters-bar {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      flex-wrap: wrap;
    }
    .filter-item {
      display: flex;
      align-items: center;
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      padding: 0 10px;
      height: 36px;
      gap: 6px;
    }
    .select-icon {
      color: var(--text-muted);
    }
    .custom-select {
      border: none;
      background: transparent;
      color: var(--text-primary);
      font-size: 13px;
      font-weight: 500;
      outline: none;
      cursor: pointer;
    }
    .segmented-control {
      display: flex;
      align-items: center;
      background: var(--bg-nested);
      border-radius: var(--radius-button);
      padding: 2px;
      height: 36px;
      box-sizing: border-box;
    }
    .seg-btn {
      background: transparent;
      border: none;
      padding: 6px 12px;
      border-radius: calc(var(--radius-button) - 2px);
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
      cursor: pointer;
      transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
    }
    .seg-btn.is-active {
      background: var(--bg-tile);
      color: var(--text-primary);
      border: 1px solid var(--border-tile);
    }
    .spacer {
      flex: 1;
    }
    .live-toggle {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
    .live-label {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary);
    }
  `,
})
export class AuditFiltersComponent {
  readonly period = input<PeriodFilter>('today');
  readonly clientFilter = input<string>('');
  readonly toolFilter = input<string>('');
  readonly segment = input<SegmentFilter>('all');
  readonly liveWatch = input<boolean>(true);
  readonly clients = input<string[]>([]);
  readonly tools = input<string[]>([]);

  readonly periodChange = output<PeriodFilter>();
  readonly clientChange = output<string>();
  readonly toolChange = output<string>();
  readonly segmentChange = output<SegmentFilter>();
  readonly liveWatchChange = output<boolean>();
}
