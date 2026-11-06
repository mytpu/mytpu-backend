import { Component, computed, inject, input, output } from '@angular/core';
import { I18n } from '../../../core/i18n/i18n.service';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SelectComponent, SelectOption } from '../../../ui/select/select.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';

export type SegmentFilter = 'all' | 'errors' | 'slow';
export type PeriodFilter = 'today' | '24h' | '7d' | '30d';

@Component({
  selector: 'app-audit-filters',
  standalone: true,
  imports: [TPipe, SelectComponent, ToggleComponent],
  template: `
    <div class="filters-bar">
      <app-select
        size="sm"
        icon="calendar"
        [options]="periodOptions"
        [value]="period()"
        (valueChange)="periodChange.emit($event)"
      />
      <app-select size="sm" [options]="clientOptions()" [value]="clientFilter()" (valueChange)="clientChange.emit($event)" />
      <app-select size="sm" [options]="toolOptions()" [value]="toolFilter()" (valueChange)="toolChange.emit($event)" />

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
  private readonly i18n = inject(I18n);

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

  readonly periodOptions: SelectOption<PeriodFilter>[] = (['today', '24h', '7d', '30d'] as const).map((value) => ({
    value,
    label: this.i18n.t(`mcp.audit.filterPeriod${value === 'today' ? 'Today' : value}`),
  }));
  readonly clientOptions = computed(() => [
    { value: '', label: this.i18n.t('mcp.audit.filterClientAll') },
    ...this.clients().map((c) => ({ value: c, label: c })),
  ]);
  readonly toolOptions = computed(() => [
    { value: '', label: this.i18n.t('mcp.audit.filterToolAll') },
    ...this.tools().map((t) => ({ value: t, label: t })),
  ]);
}
