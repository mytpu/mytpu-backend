import { Component, input, output } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { IconComponent, IconName } from '../../../ui/icon/icon.component';

export interface ModeCardConfig {
  id: string;
  titleKey: string;
  descKey: string;
  icon: IconName;
}

@Component({
  selector: 'app-mode-cards',
  standalone: true,
  imports: [TPipe, BadgeComponent, IconComponent],
  template: `
    <div class="modes-grid">
      @for (m of MODES; track m.id) {
        <div
          [class]="'mode-card' + (currentMode() === m.id ? ' is-current' : '') + (selectedMode() === m.id ? ' is-selected' : '')"
          (click)="canSelect() ? modeSelect.emit(m.id) : null"
        >
          <div class="card-head">
            <div [class]="'icon-sq' + (currentMode() === m.id ? ' is-active' : '')">
              <app-icon [name]="m.icon" [size]="20" />
            </div>
            @if (currentMode() === m.id) {
              <app-badge kind="ok" [dot]="true">
                {{ 'mcp.mode.currentChip' | t }}
              </app-badge>
            }
          </div>
          <h3 class="mode-title">{{ m.titleKey | t }}</h3>
          <p class="mode-desc">{{ m.descKey | t }}</p>
          <span class="mode-tag">{{ m.id }}</span>
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .modes-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: var(--space-4);
    }
    .mode-card {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      padding: var(--space-4);
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      cursor: pointer;
      box-sizing: border-box;
      min-height: 176px;
      transition: border-color var(--dur-fast) var(--ease);
    }
    .mode-card:hover {
      border-color: var(--green-action);
    }
    .mode-card.is-current {
      border: 2px solid var(--green-action);
    }
    .mode-card.is-selected:not(.is-current) {
      border-color: var(--info-icon);
      background: var(--bg-nested);
    }
    .card-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .icon-sq {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: var(--bg-chip);
      color: var(--text-secondary);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .icon-sq.is-active {
      background: var(--green-soft);
      color: var(--green-text);
    }
    .mode-title {
      margin: 0;
      font-size: 16px;
      font-weight: 700;
      color: var(--text-primary);
    }
    .mode-desc {
      margin: 0;
      font-size: 13px;
      line-height: 1.4;
      color: var(--text-secondary);
      flex: 1;
    }
    .mode-tag {
      font-family: var(--font-mono);
      font-size: 12px;
      color: var(--text-muted);
    }
  `,
})
export class ModeCardsComponent {
  readonly currentMode = input.required<string>();
  readonly selectedMode = input<string | null>(null);
  readonly canSelect = input<boolean>(true);

  readonly modeSelect = output<string>();

  readonly MODES: ModeCardConfig[] = [
    { id: 'normal', titleKey: 'mcp.mode.normalTitle', descKey: 'mcp.mode.normalDesc', icon: 'check' },
    { id: 'read_only', titleKey: 'mcp.mode.readOnlyTitle', descKey: 'mcp.mode.readOnlyDesc', icon: 'eye' },
    { id: 'internal_only', titleKey: 'mcp.mode.internalOnlyTitle', descKey: 'mcp.mode.internalOnlyDesc', icon: 'shield' },
  ];
}
