import { Component, input, output } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { ToolView } from './integrations.model';

@Component({
  selector: 'app-tool-row',
  standalone: true,
  imports: [TPipe, BadgeComponent, ToggleComponent],
  template: `
    <div
      [class]="'tool-row' + (selected() ? ' is-selected' : '')"
      (click)="select.emit()"
    >
      <div class="tool-info">
        <span class="tool-name">{{ tool().name }}</span>
        <span class="tool-desc">{{ tool().description }}</span>
      </div>
      <div class="tool-badges">
        @if (tool().sensitivity === 'personal') {
          <app-badge kind="warn">{{ 'mcp.integrations.personalData' | t }}</app-badge>
        }
        @if (tool().kind === 'write') {
          <app-badge kind="danger">{{ 'mcp.integrations.writeAccess' | t }}</app-badge>
        }
        @if (tool().override) {
          <app-badge kind="info">{{ 'mcp.integrations.customLimits' | t }}</app-badge>
        }
      </div>
      <div class="toggle-wrap" (click)="$event.stopPropagation()">
        <app-toggle
          [checked]="tool().enabled && parentEnabled()"
          [disabled]="!canChange() || !parentEnabled()"
          (changed)="toggle.emit($event)"
        />
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .tool-row {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: 8px 12px;
      border-radius: var(--radius-button);
      cursor: pointer;
    }
    .tool-row:hover {
      background: var(--bg-nested);
    }
    .tool-row.is-selected {
      background: var(--green-soft);
    }
    .tool-info {
      display: flex;
      flex-direction: column;
      flex: 1;
      gap: 2px;
    }
    .tool-name {
      font-family: var(--font-mono);
      font-size: 13px;
      font-weight: 600;
      color: var(--text-primary);
    }
    .tool-desc {
      font-size: 12px;
      color: var(--text-secondary);
    }
    .tool-badges {
      display: flex;
      align-items: center;
      gap: 6px;
    }
  `,
})
export class ToolRowComponent {
  readonly tool = input.required<ToolView>();
  readonly selected = input<boolean>(false);
  readonly parentEnabled = input<boolean>(true);
  readonly canChange = input<boolean>(true);

  readonly select = output<void>();
  readonly toggle = output<boolean>();
}
