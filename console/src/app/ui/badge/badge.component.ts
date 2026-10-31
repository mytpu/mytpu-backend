import { Component, input } from '@angular/core';

export type BadgeKind = 'ok' | 'info' | 'warn' | 'danger' | 'neutral' | 'graphite';

@Component({
  selector: 'app-badge',
  standalone: true,
  template: `
    <span [class]="'badge ' + kind()">
      @if (dot()) {
        <span class="dot"></span>
      }
      <ng-content />
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 3px 10px;
      border-radius: var(--radius-pill);
      font-size: 12px;
      font-weight: 700;
      line-height: 1.2;
      white-space: nowrap;
    }
    .dot {
      width: 6px;
      height: 6px;
      border-radius: var(--radius-pill);
      background-color: currentColor;
    }

    .badge.ok {
      background: var(--green-soft);
      color: var(--green-text);
    }
    .badge.info {
      background: var(--info-soft);
      color: var(--info-text);
    }
    .badge.warn {
      background: var(--warn-soft);
      color: var(--warn-text);
    }
    .badge.danger {
      background: var(--danger-soft);
      color: var(--danger-text);
    }
    .badge.neutral {
      background: var(--bg-chip);
      color: var(--text-secondary);
    }
    .badge.graphite {
      background: var(--graphite-bg);
      color: var(--graphite-text);
    }
  `,
})
export class BadgeComponent {
  readonly kind = input<BadgeKind>('neutral');
  readonly dot = input<boolean>(false);
}
