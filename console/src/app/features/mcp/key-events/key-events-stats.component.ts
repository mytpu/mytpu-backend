import { Component, input } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { IconComponent } from '../../../ui/icon/icon.component';

@Component({
  selector: 'app-key-events-stats',
  standalone: true,
  imports: [TPipe, IconComponent],
  template: `
    <div class="stats-grid">
      <div class="stat-card" [class.danger]="decoyCount() > 0">
        <div class="stat-head">
          <span class="stat-label">{{ 'mcp.events.statDecoy' | t }}</span>
          <app-icon name="target" [size]="18" class="stat-icon" />
        </div>
        <div class="stat-value">{{ decoyCount() }}</div>
        <div class="stat-sub">
          @if (decoyCount() > 0 && decoyPlace()) {
            {{ 'mcp.events.statDecoySub' | t: { place: decoyPlace() } }}
          } @else {
            {{ 'mcp.events.statDecoyNone' | t }}
          }
        </div>
      </div>

      <div class="stat-card warn">
        <div class="stat-head">
          <span class="stat-label">{{ 'mcp.events.statSuspended' | t }}</span>
          <app-icon name="lock" [size]="18" class="stat-icon" />
        </div>
        <div class="stat-value">{{ suspendedCount() }}</div>
        <div class="stat-sub">
          {{ 'mcp.events.statSuspendedSub' | t: { decoyCount: groupSuspendedCount(), inactivityCount: inactiveCount(), otherCount: otherSuspendedCount() } }}
        </div>
      </div>

      <div class="stat-card warn">
        <div class="stat-head">
          <span class="stat-label">{{ 'mcp.events.statExpiring' | t }}</span>
          <app-icon name="clock" [size]="18" class="stat-icon" />
        </div>
        <div class="stat-value">{{ expiringCount() }}</div>
        <div class="stat-sub">{{ 'mcp.events.statExpiringSub' | t }}</div>
      </div>

      <div class="stat-card info">
        <div class="stat-head">
          <span class="stat-label">{{ 'mcp.events.statSelfRotated' | t }}</span>
          <app-icon name="refresh" [size]="18" class="stat-icon" />
        </div>
        <div class="stat-value">{{ selfRotatedCount() }}</div>
        <div class="stat-sub">{{ 'mcp.events.statSelfRotatedSub' | t }}</div>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: var(--space-4);
      width: 100%;
    }
    .stat-card {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      padding: var(--space-4);
      display: flex;
      flex-direction: column;
      gap: 6px;
      box-sizing: border-box;
    }
    .stat-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      color: var(--text-secondary);
    }
    .stat-label {
      font-size: 13px;
      font-weight: 600;
    }
    .stat-icon {
      color: var(--text-muted);
    }
    .stat-value {
      font-size: 28px;
      font-weight: 800;
      color: var(--text-primary);
      line-height: 1;
    }
    .stat-sub {
      font-size: 12px;
      color: var(--text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .stat-card.danger {
      border-color: var(--danger-text);
      background: var(--danger-soft);
    }
    .stat-card.danger .stat-value,
    .stat-card.danger .stat-icon {
      color: var(--danger-text);
    }
    .stat-card.warn .stat-icon {
      color: var(--warn-icon);
    }
    .stat-card.info .stat-icon {
      color: var(--info-icon);
    }
    @media (max-width: 1200px) {
      .stats-grid {
        grid-template-columns: repeat(2, 1fr);
      }
    }
  `,
})
export class KeyEventsStatsComponent {
  readonly decoyCount = input.required<number>();
  readonly decoyPlace = input<string>('');
  readonly suspendedCount = input.required<number>();
  readonly groupSuspendedCount = input.required<number>();
  readonly inactiveCount = input.required<number>();
  readonly otherSuspendedCount = input.required<number>();
  readonly expiringCount = input.required<number>();
  readonly selfRotatedCount = input.required<number>();
}
