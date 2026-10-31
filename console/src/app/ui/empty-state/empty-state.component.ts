import { Component, input, output } from '@angular/core';
import { IconComponent, IconName } from '../icon/icon.component';
import { ButtonComponent } from '../button/button.component';

@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [IconComponent, ButtonComponent],
  template: `
    <div class="empty-state">
      <div class="icon-circle">
        <app-icon [name]="icon()" [size]="26" [strokeWidth]="2.5" />
      </div>
      @if (title()) {
        <h3 class="title">{{ title() }}</h3>
      }
      @if (description()) {
        <p class="description">{{ description() }}</p>
      }
      @if (actionText()) {
        <div class="action-slot">
          <app-button kind="quiet" (btnClick)="action.emit()">
            {{ actionText() }}
          </app-button>
        </div>
      }
      <ng-content />
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .empty-state {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      padding: var(--space-8);
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: var(--space-3);
      box-sizing: border-box;
    }
    .icon-circle {
      width: 56px;
      height: 56px;
      border-radius: var(--radius-pill);
      background-color: var(--green-soft);
      color: var(--green-text);
      display: flex;
      align-items: center;
      justify-content: center;
      margin-bottom: 4px;
    }
    .title {
      margin: 0;
      font-size: 18px;
      font-weight: 800;
      color: var(--text-primary);
      line-height: 1.2;
    }
    .description {
      margin: 0;
      font-size: 14px;
      color: var(--text-secondary);
      max-width: 420px;
      line-height: 1.5;
    }
    .action-slot {
      margin-top: 4px;
    }
  `,
})
export class EmptyStateComponent {
  readonly icon = input<IconName>('check');
  readonly title = input<string | null>(null);
  readonly description = input<string | null>(null);
  readonly actionText = input<string | null>(null);

  readonly action = output<void>();
}
