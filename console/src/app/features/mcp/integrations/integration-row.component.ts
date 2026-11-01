import { Component, inject, input, output } from '@angular/core';
import { I18n } from '../../../core/i18n/i18n.service';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { IconComponent, IconName } from '../../../ui/icon/icon.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { IntegrationStatus } from './integrations.model';

@Component({
  selector: 'app-integration-row',
  standalone: true,
  imports: [TPipe, BadgeComponent, IconComponent, ToggleComponent],
  template: `
    <div class="integration-row" (click)="toggleExpand.emit()">
      <button type="button" class="expand-btn" [attr.aria-expanded]="expanded()">
        <app-icon [name]="expanded() ? 'chevDown' : 'chevRight'" [size]="16" />
      </button>
      <div [class]="'source-sq ' + getSourceClass(integration().id)">
        <app-icon [name]="getSourceIcon(integration().id)" [size]="18" />
      </div>
      <div class="title-col">
        <span class="integ-title">{{ integration().title }}</span>
        <span class="integ-sub">{{ getDomain(integration().id) }}</span>
      </div>
      <div class="chips-col">
        @if (integration().missingSecrets.length) {
          <app-badge kind="warn">{{ 'mcp.integrations.serviceStatusNeedsKey' | t }}</app-badge>
        } @else if (integration().enabled) {
          <app-badge kind="ok">{{ 'mcp.integrations.serviceStatusWorking' | t }}</app-badge>
        } @else {
          <app-badge kind="neutral">{{ 'mcp.integrations.serviceStatusDisabled' | t }}</app-badge>
        }
      </div>
      <div class="toggle-wrap" (click)="$event.stopPropagation()">
        <app-toggle
          [checked]="integration().enabled"
          [disabled]="!canChange()"
          (changed)="toggle.emit($event)"
        />
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .integration-row {
      display: flex;
      align-items: center;
      gap: var(--space-3);
      padding: 10px 8px;
      border-radius: var(--radius-card);
      cursor: pointer;
    }
    .integration-row:hover {
      background: var(--bg-nested);
    }
    .expand-btn {
      background: transparent;
      border: none;
      padding: 0;
      color: var(--text-muted);
      cursor: pointer;
      display: flex;
      align-items: center;
    }
    .source-sq {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .source-sq.src-lk { background: var(--source-blue-soft); color: var(--source-blue); }
    .source-sq.src-rasp { background: var(--source-green-soft); color: var(--source-green); }
    .source-sq.src-lms { background: var(--source-orange-soft); color: var(--source-orange); }
    .source-sq.src-help { background: var(--source-violet-soft); color: var(--source-violet); }
    .source-sq.src-devdocs { background: var(--source-grey-soft); color: var(--source-grey); }
    .source-sq.src-site { background: var(--source-green-soft); color: var(--source-green); }
    .source-sq.src-mail { background: var(--source-blue-soft); color: var(--source-blue); }
    .title-col {
      display: flex;
      flex-direction: column;
      flex: 1;
      gap: 2px;
    }
    .integ-title {
      font-size: 15px;
      font-weight: 700;
      color: var(--text-primary);
    }
    .integ-sub {
      font-size: 12px;
      color: var(--text-secondary);
    }
    .chips-col {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
  `,
})
export class IntegrationRowComponent {
  private readonly i18n = inject(I18n);
  readonly integration = input.required<IntegrationStatus>();
  readonly expanded = input<boolean>(false);
  readonly canChange = input<boolean>(true);

  readonly toggleExpand = output<void>();
  readonly toggle = output<boolean>();

  getSourceIcon(id: string): IconName {
    switch (id) {
      case 'lk': return 'user';
      case 'rasp': return 'calendar';
      case 'lms': return 'cap';
      case 'help': return 'wrench';
      case 'devdocs': return 'book';
      case 'site': return 'search';
      case 'mail': return 'mail';
      default: return 'plug';
    }
  }

  getSourceClass(id: string): string {
    return `src-${id}`;
  }

  getDomain(id: string): string {
    switch (id) {
      case 'lk': return 'lk.tpu.ru';
      case 'rasp': return 'rasp.tpu.ru';
      case 'lms': return 'lms.tpu.ru';
      case 'help': return 'help.tpu.ru';
      case 'devdocs': return this.i18n.t('mcp.integrations.hostDevdocs');
      case 'site': return this.i18n.t('mcp.integrations.hostSite');
      case 'mail': return 'mail.tpu.ru';
      default: return '';
    }
  }
}
