import { Component, input } from '@angular/core';
import { CardComponent } from '../card/card.component';
import { BadgeComponent, BadgeKind } from '../badge/badge.component';
import { IconName } from '../icon/icon.component';

@Component({
  selector: 'app-stat',
  standalone: true,
  imports: [CardComponent, BadgeComponent],
  template: `
    <app-card [label]="label()" [icon]="icon()">
      <div class="stat-content">
        <div class="val-row">
          <span class="value">{{ value() }}</span>
          @if (unit()) {
            <span class="unit">{{ unit() }}</span>
          }
        </div>
        @if (chip()) {
          <div class="chip-row">
            <app-badge [kind]="chipKind()">{{ chip() }}</app-badge>
          </div>
        }
      </div>
    </app-card>
  `,
  styles: `
    :host {
      display: block;
    }
    .stat-content {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .val-row {
      display: flex;
      align-items: baseline;
      gap: 6px;
    }
    .value {
      font-size: 28px;
      font-weight: 800;
      letter-spacing: -0.5px;
      line-height: 1.1;
      color: var(--text-primary);
    }
    .unit {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .chip-row {
      margin-top: 4px;
    }
  `,
})
export class StatComponent {
  readonly label = input<string | null>(null);
  readonly value = input.required<string | number>();
  readonly unit = input<string | null>(null);
  readonly icon = input<IconName | null>(null);
  readonly chip = input<string | null>(null);
  readonly chipKind = input<BadgeKind>('neutral');
}
