import { Component, computed, inject, input } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { LiveNumComponent } from './live-num.component';
import { SeriesPoint } from './overview.model';

@Component({
  selector: 'app-now-tile',
  standalone: true,
  imports: [TPipe, LiveNumComponent],
  template: `
    <div class="now-box">
      <div class="now-head">
        <span class="now-status">{{ 'mcp.overview.nowWorking' | t: { mode: mode() } }}</span>
      </div>
      <div class="now-row">
        <span class="now-calls"><app-live-num [value]="totalCalls()" /></span>
        <span class="now-period">{{ 'mcp.overview.nowCalls' | t: { calls: '', period: periodLabel() } }}</span>
      </div>
      <div class="now-summary">
        <span>{{ 'mcp.overview.nowStatsErrors' | t }}</span>
        <app-live-num [value]="totalErrors()" />
        <span>{{ 'mcp.overview.nowStatsRest' | t: {
          percent: errorPercent(),
          p95: p95Overall(),
          keys: activeClientsCount()
        } }}</span>
      </div>
      <div class="now-bars">
        @for (b of bars(); track $index; let last = $last) {
          <div class="now-bar" [style.transform]="'scaleY(' + b.scale + ')'" [class.current]="last"></div>
        }
      </div>
      <div class="now-axis" [class.is-7d]="is7d()">
        @for (label of axisLabels(); track $index) {
          <span>{{ label }}</span>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: block; width: 100%; }
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
    .now-head { display: flex; align-items: center; }
    .now-status { font-size: 12px; font-weight: 600; }
    .now-row { display: flex; align-items: baseline; gap: var(--space-2); }
    .now-calls { font-size: 34px; font-weight: 800; line-height: 1; color: var(--green-now-title); }
    .now-period { font-size: 14px; font-weight: 600; }
    .now-summary {
      font-size: 14px;
      font-weight: 400;
      color: var(--green-now-title);
      display: flex;
      align-items: baseline;
      gap: var(--space-1);
    }
    .now-bars {
      display: flex;
      align-items: flex-end;
      gap: 3px;
      height: 56px;
      padding-top: 10px;
      box-sizing: border-box;
    }
    .now-bar {
      flex: 1;
      min-width: 0;
      height: 46px;
      background-color: var(--green-now-text);
      border-radius: 2px;
      opacity: 0.45;
      transform-origin: bottom;
      transition: transform var(--dur-slow) var(--ease);
    }
    .now-bar.current { opacity: 1; }
    .now-axis { display: flex; justify-content: space-between; font-size: 11px; font-weight: 500; }
    .now-axis.is-7d span { flex: 1; text-align: center; }
  `,
})
export class NowTileComponent {
  private readonly i18n = inject(I18n);

  readonly mode = input.required<string>();
  readonly totalCalls = input.required<number>();
  readonly periodLabel = input.required<string>();
  readonly totalErrors = input.required<number>();
  readonly errorPercent = input.required<string>();
  readonly p95Overall = input.required<string>();
  readonly activeClientsCount = input.required<number>();
  readonly series = input<SeriesPoint[]>([]);
  readonly hours = input<number>(24);

  readonly is7d = computed(() => this.hours() === 168);

  readonly bars = computed(() => {
    const s = this.series();
    if (!s || s.length === 0) return [];
    const max = Math.max(...s.map((p) => p.calls), 0);
    return s.map((p) => {
      const ratio = max > 0 ? p.calls / max : 0;
      const scale = max > 0 ? Math.max(0.065, ratio) : 0.065;
      return { calls: p.calls, errors: p.errors, scale: Number(scale.toFixed(4)) };
    });
  });

  readonly axisLabels = computed<string[]>(() => {
    const s = this.series();
    if (!s || s.length === 0) return [];
    const h = this.hours();
    const nowText = this.i18n.t('mcp.overview.axisNow');

    if (h <= 24) {
      const timeFmt = new Intl.DateTimeFormat('ru-RU', { timeZone: 'Asia/Tomsk', hour: '2-digit', minute: '2-digit' });
      const n = s.length;
      if (n === 1) return [nowText];
      const i1 = Math.min(n - 1, Math.round(n * 0.25));
      const i2 = Math.min(n - 1, Math.round(n * 0.5));
      const i3 = Math.min(n - 1, Math.round(n * 0.75));
      return [
        timeFmt.format(new Date(s[0].at)),
        timeFmt.format(new Date(s[i1].at)),
        timeFmt.format(new Date(s[i2].at)),
        timeFmt.format(new Date(s[i3].at)),
        nowText,
      ];
    }

    if (h <= 168) {
      const dayFmt = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Tomsk', weekday: 'short' });
      return s.map((p) => this.i18n.t(`mcp.overview.weekday${dayFmt.format(new Date(p.at))}`));
    }

    const dateFmt = new Intl.DateTimeFormat('ru-RU', { timeZone: 'Asia/Tomsk', day: '2-digit', month: '2-digit' });
    const n = s.length;
    if (n <= 5) return s.map((p) => dateFmt.format(new Date(p.at)));
    const count = 5;
    return Array.from({ length: count }, (_, i) => Math.min(n - 1, Math.round((i * (n - 1)) / (count - 1))))
      .map((idx) => dateFmt.format(new Date(s[idx].at)));
  });
}
