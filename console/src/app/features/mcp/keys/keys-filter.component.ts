import { Component, input, model, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';

@Component({
  selector: 'app-keys-filter',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, IconComponent],
  template: `
    <div class="keys-toolbar">
      <div class="search-box">
        <app-icon name="search" [size]="16" class="search-icon" />
        <input
          type="text"
          class="search-input"
          [placeholder]="'mcp.keys.searchPlaceholder' | t"
          [ngModel]="searchQuery()"
          (ngModelChange)="searchQuery.set($event)"
        />
        @if (searchQuery()) {
          <button type="button" class="clear-btn" (click)="searchQuery.set('')">
            <app-icon name="x" [size]="14" />
          </button>
        }
      </div>

      <div class="toolbar-spacer"></div>

      <app-button
        kind="primary"
        icon="plus"
        [disabled]="!canChange()"
        [disabledTitle]="'mcp.keys.readonlyHint' | t"
        (btnClick)="newKeyClick.emit()"
      >
        {{ 'mcp.keys.newKey' | t }}
      </app-button>
    </div>

    <div class="filters-bar">
      <div class="seg-controls">
        <button
          type="button"
          class="seg-btn"
          [class.active]="statusFilter() === 'active'"
          (click)="statusFilter.set('active')"
        >
          {{ 'mcp.keys.filterActive' | t }} · {{ activeCount() }}
        </button>
        <button
          type="button"
          class="seg-btn"
          [class.active]="statusFilter() === 'expiring'"
          (click)="statusFilter.set('expiring')"
        >
          {{ 'mcp.keys.filterExpiring' | t }} · {{ expiringCount() }}
        </button>
        <button
          type="button"
          class="seg-btn"
          [class.active]="statusFilter() === 'revoked'"
          (click)="statusFilter.set('revoked')"
        >
          {{ 'mcp.keys.filterRevoked' | t }} · {{ revokedCount() }}
        </button>
      </div>

      <div class="toolbar-spacer"></div>

      <select
        class="scope-select"
        [ngModel]="scopeFilter()"
        (ngModelChange)="scopeFilter.set($event)"
      >
        <option value="all">{{ 'mcp.keys.filterAll' | t }}</option>
        @for (s of allScopes(); track s) {
          <option [value]="s">{{ s }}</option>
        }
      </select>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      width: 100%;
    }
    .keys-toolbar, .filters-bar {
      display: flex;
      align-items: center;
      gap: var(--space-3);
    }
    .toolbar-spacer {
      flex: 1;
    }
    .search-box {
      display: inline-flex;
      align-items: center;
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      padding: 0 var(--space-3);
      height: var(--height-field);
      width: 280px;
      box-sizing: border-box;
      gap: var(--space-2);
    }
    .search-icon {
      color: var(--text-muted);
    }
    .search-input {
      border: none;
      background: transparent;
      color: var(--text-primary);
      font-family: var(--font-sans);
      font-size: 14px;
      width: 100%;
      outline: none;
    }
    .clear-btn {
      background: transparent;
      border: none;
      color: var(--text-muted);
      cursor: pointer;
      padding: 2px;
      display: flex;
      align-items: center;
    }
    .seg-controls {
      display: inline-flex;
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      padding: 2px;
      gap: 2px;
    }
    .seg-btn {
      background: transparent;
      border: none;
      border-radius: calc(var(--radius-button) - 2px);
      padding: 6px var(--space-3);
      font-family: var(--font-sans);
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
      cursor: pointer;
    }
    .seg-btn.active {
      background: var(--bg-nested);
      color: var(--text-primary);
    }
    .scope-select {
      height: 36px;
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      color: var(--text-primary);
      font-family: var(--font-sans);
      font-size: 13px;
      font-weight: 600;
      padding: 0 var(--space-3);
      outline: none;
    }
  `,
})
export class KeysFilterComponent {
  readonly searchQuery = model<string>('');
  readonly statusFilter = model<'active' | 'expiring' | 'revoked'>('active');
  readonly scopeFilter = model<string>('all');

  readonly activeCount = input.required<number>();
  readonly expiringCount = input.required<number>();
  readonly revokedCount = input.required<number>();
  readonly allScopes = input.required<string[]>();
  readonly canChange = input.required<boolean>();

  readonly newKeyClick = output<void>();
}
