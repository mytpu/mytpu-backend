import { Component, input, output, signal } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { KeyEventView } from './key-events.model';

export interface SuspendedKeyInfo {
  id: string;
  owner: string;
  prefix: string;
}

@Component({
  selector: 'app-key-events-alarm',
  standalone: true,
  imports: [TPipe, BadgeComponent, ButtonComponent, IconComponent],
  template: `
    <div class="alarm-box">
      <div class="alarm-head">
        <app-icon name="alert" [size]="20" class="alarm-icon" />
        <span class="alarm-title">
          {{ 'mcp.events.alarmTitle' | t: {
            owner: decoyEvent().owner || decoyEvent().clientId,
            time: formatTime(decoyEvent().at),
            network: decoyEvent().network || '—'
          } }}
        </span>
        <app-button
          kind="quiet"
          size="sm"
          icon="target"
          (btnClick)="selectGroup.emit(decoyEvent().clientId)"
        >
          {{ 'mcp.events.alarmBtnGroup' | t }}
        </app-button>
      </div>

      <div class="alarm-body">
        <div class="alarm-group">
          <div class="group-title">{{ 'mcp.events.alarmSuspendedTitle' | t }}</div>
          @if (suspendedKeys().length > 0) {
            <div class="members-list">
              @for (k of suspendedKeys(); track k.id) {
                <div class="member-row">
                  <app-badge kind="danger">{{ 'mcp.events.alarmSuspendedBadge' | t }}</app-badge>
                  <span class="member-owner">{{ k.owner }}</span>
                  <code class="member-pref">{{ k.prefix }}</code>
                </div>
              }
            </div>
          } @else {
            <span class="no-keys-text">—</span>
          }
        </div>

        <div class="alarm-todo">
          <div class="todo-title">{{ 'mcp.events.alarmTodoTitle' | t }}</div>
          <div class="todo-list">
            @for (todo of todos; track todo.idx) {
              <label class="todo-item">
                <input
                  type="checkbox"
                  class="todo-check"
                  [checked]="checkedItems().has(todo.idx)"
                  (change)="toggleTodo(todo.idx)"
                />
                <span class="todo-label" [class.checked]="checkedItems().has(todo.idx)">
                  {{ todo.key | t }}
                </span>
              </label>
            }
          </div>
        </div>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .alarm-box {
      background: var(--danger-soft);
      border: 1px solid var(--danger-text);
      border-radius: var(--radius-card);
      padding: var(--space-4) var(--space-5);
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      box-sizing: border-box;
    }
    .alarm-head {
      display: flex;
      align-items: center;
      gap: var(--space-3);
    }
    .alarm-icon { color: var(--danger-text); flex-shrink: 0; }
    .alarm-title { font-size: 15px; font-weight: 700; color: var(--danger-text); flex: 1; }
    .alarm-body { display: flex; gap: var(--space-5); align-items: flex-start; }
    .alarm-group { flex: 1; display: flex; flex-direction: column; gap: var(--space-2); }
    .group-title, .todo-title { font-size: 13px; font-weight: 700; color: var(--text-primary); }
    .members-list { display: flex; flex-direction: column; gap: 6px; }
    .member-row { display: flex; align-items: center; gap: var(--space-2); font-size: 13px; }
    .member-owner { font-weight: 600; color: var(--text-primary); }
    .member-pref { font-family: var(--font-mono); font-size: 12px; color: var(--text-secondary); }
    .no-keys-text { font-size: 13px; color: var(--text-muted); }
    .alarm-todo { width: 500px; flex-shrink: 0; display: flex; flex-direction: column; gap: var(--space-2); }
    .todo-list { display: flex; flex-direction: column; gap: 6px; }
    .todo-item { display: flex; align-items: center; gap: var(--space-2); cursor: pointer; user-select: none; }
    .todo-check { accent-color: var(--green-action); width: 16px; height: 16px; cursor: pointer; }
    .todo-label { font-size: 13px; font-weight: 500; color: var(--text-primary); line-height: 1.3; }
    .todo-label.checked { text-decoration: line-through; color: var(--text-muted); }
    @media (max-width: 1200px) {
      .alarm-body { flex-direction: column; }
      .alarm-todo { width: 100%; }
    }
  `,
})
export class KeyEventsAlarmComponent {
  readonly decoyEvent = input.required<KeyEventView>();
  readonly suspendedKeys = input<SuspendedKeyInfo[]>([]);

  readonly selectGroup = output<string>();

  readonly checkedItems = signal<Set<number>>(new Set([1]));

  readonly todos = [
    { idx: 1, key: 'mcp.events.alarmTodo1' },
    { idx: 2, key: 'mcp.events.alarmTodo2' },
    { idx: 3, key: 'mcp.events.alarmTodo3' },
    { idx: 4, key: 'mcp.events.alarmTodo4' },
  ];

  toggleTodo(idx: number): void {
    const cur = new Set(this.checkedItems());
    if (cur.has(idx)) {
      cur.delete(idx);
    } else {
      cur.add(idx);
    }
    this.checkedItems.set(cur);
  }

  formatTime(iso: string): string {
    const d = new Date(iso);
    return `${d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })} ${d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`;
  }
}
