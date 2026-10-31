import { Component, input } from '@angular/core';
import { IconComponent } from '../icon/icon.component';

export interface StepItem {
  label: string;
  description?: string;
}

@Component({
  selector: 'app-steps',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="steps-wrapper">
      @for (step of steps(); track $index; let last = $last) {
        <div
          [class]="'step-item' +
            ($index < activeIndex() ? ' is-completed' : '') +
            ($index === activeIndex() ? ' is-active' : '')"
        >
          <div class="step-badge">
            @if ($index < activeIndex()) {
              <app-icon name="check" [size]="14" [strokeWidth]="2.5" />
            } @else {
              <span>{{ $index + 1 }}</span>
            }
          </div>
          <div class="step-info">
            <span class="step-label">{{ step.label }}</span>
            @if (step.description) {
              <span class="step-desc">{{ step.description }}</span>
            }
          </div>
          @if (!last) {
            <div class="step-connector"></div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .steps-wrapper {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      width: 100%;
    }
    .step-item {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      flex: 1;
      position: relative;
    }
    .step-badge {
      width: 28px;
      height: 28px;
      border-radius: var(--radius-pill);
      background-color: var(--bg-chip);
      color: var(--text-muted);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 13px;
      font-weight: 700;
      flex-shrink: 0;
      transition: background-color var(--dur-fast) var(--ease),
                  color var(--dur-fast) var(--ease);
    }
    .step-info {
      display: flex;
      flex-direction: column;
      line-height: 1.2;
    }
    .step-label {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-muted);
      white-space: nowrap;
    }
    .step-desc {
      font-size: 11px;
      color: var(--text-secondary);
      white-space: nowrap;
    }
    .step-connector {
      flex: 1;
      height: 2px;
      background-color: var(--line);
      margin: 0 var(--space-2);
    }

    .step-item.is-active .step-badge {
      background-color: var(--green-soft);
      color: var(--green-action);
      border: 2px solid var(--green-action);
    }
    .step-item.is-active .step-label {
      color: var(--text-primary);
      font-weight: 700;
    }

    .step-item.is-completed .step-badge {
      background-color: var(--green-action);
      color: var(--text-on-action);
    }
    .step-item.is-completed .step-label {
      color: var(--text-primary);
    }
    .step-item.is-completed .step-connector {
      background-color: var(--green-action);
    }
  `,
})
export class StepsComponent {
  readonly steps = input.required<StepItem[]>();
  readonly activeIndex = input<number>(0);
}
