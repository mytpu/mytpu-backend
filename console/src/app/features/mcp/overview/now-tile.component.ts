import { Component, input } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { LiveNumComponent } from './live-num.component';

@Component({
  selector: 'app-now-tile',
  standalone: true,
  imports: [TPipe, LiveNumComponent],
  template: `
    <div class="now-box">
      <div class="now-head">
        <span class="now-status">
          {{ 'mcp.overview.nowWorking' | t: { mode: mode() } }}
        </span>
      </div>
      <div class="now-row">
        <span class="now-calls">
          <app-live-num [value]="totalCalls()" />
        </span>
        <span class="now-period">
          {{ 'mcp.overview.nowCalls' | t: { calls: '', period: periodLabel() } }}
        </span>
      </div>
      <div class="now-summary">
        {{ 'mcp.overview.nowStats' | t: {
          errors: totalErrors(),
          percent: errorPercent(),
          p95: p95Overall(),
          keys: activeClientsCount()
        } }}
      </div>
      <div class="now-bars">
        @for (v of mockBars(); track $index; let last = $last) {
          <div
            class="now-bar"
            [style.height.px]="v * 0.45"
            [class.current]="last"
          ></div>
        }
      </div>
      <div class="now-axis">
        <span>{{ 'mcp.overview.axis00' | t }}</span>
        <span>{{ 'mcp.overview.axis06' | t }}</span>
        <span>{{ 'mcp.overview.axis12' | t }}</span>
        <span>{{ 'mcp.overview.axis18' | t }}</span>
        <span>{{ 'mcp.overview.axisNow' | t }}</span>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .now-box {
      background: var(--green-now-bg);
      border: 1px solid var(--green-now-border);
      border-radius: var(--radius-now);
      padding: 18px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      color: var(--green-now-text);
      box-sizing: border-box;
    }
    .now-head {
      display: flex;
      align-items: center;
    }
    .now-status {
      font-size: 12px;
      font-weight: 600;
    }
    .now-row {
      display: flex;
      align-items: baseline;
      gap: var(--space-2);
    }
    .now-calls {
      font-size: 34px;
      font-weight: 800;
      line-height: 1;
      color: var(--green-now-title);
    }
    .now-period {
      font-size: 14px;
      font-weight: 600;
    }
    .now-summary {
      font-size: 14px;
      font-weight: 400;
      color: var(--green-now-title);
    }
    .now-bars {
      display: flex;
      align-items: flex-end;
      gap: 4px;
      height: 56px;
      padding-top: 10px;
    }
    .now-bar {
      flex: 1;
      min-height: 3px;
      background-color: var(--green-now-text);
      border-radius: 2px;
      opacity: 0.45;
      transition: height var(--dur-slow) var(--ease);
    }
    .now-bar.current {
      opacity: 1;
    }
    .now-axis {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
      font-weight: 500;
    }
  `,
})
export class NowTileComponent {
  readonly mode = input.required<string>();
  readonly totalCalls = input.required<number>();
  readonly periodLabel = input.required<string>();
  readonly totalErrors = input.required<number>();
  readonly errorPercent = input.required<string>();
  readonly p95Overall = input.required<string>();
  readonly activeClientsCount = input.required<number>();
  readonly mockBars = input<number[]>([
    22, 18, 12, 9, 8, 10, 18, 35, 60, 78, 85, 90, 82, 88, 92, 80, 74, 70, 62, 55, 48, 40, 33, 27,
  ]);
}
