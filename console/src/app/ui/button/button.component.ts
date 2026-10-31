import { Component, input, output } from '@angular/core';
import { IconComponent, IconName } from '../icon/icon.component';

export type ButtonKind = 'primary' | 'outline' | 'ghost' | 'danger' | 'dark' | 'quiet';

@Component({
  selector: 'app-button',
  standalone: true,
  imports: [IconComponent],
  template: `
    <button
      [type]="type()"
      [disabled]="disabled()"
      [attr.title]="disabled() ? disabledTitle() : null"
      [class]="'btn ' + kind() + ' size-' + size() + (fullWidth() ? ' full-w' : '')"
      (click)="handleClick($event)"
    >
      @if (icon()) {
        <app-icon [name]="icon()!" [size]="iconSize()" />
      }
      <span class="btn-label">
        <ng-content />
      </span>
    </button>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    :host(.full-w) {
      display: block;
      width: 100%;
    }
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
      border-radius: var(--radius-button);
      font-family: var(--font-sans);
      font-size: 14px;
      font-weight: 600;
      line-height: 1;
      padding: 0 var(--space-4);
      cursor: pointer;
      border: 1px solid transparent;
      transition: opacity var(--dur-fast) var(--ease),
                  background-color var(--dur-fast) var(--ease),
                  border-color var(--dur-fast) var(--ease);
      white-space: nowrap;
      text-decoration: none;
      box-sizing: border-box;
    }

    .size-sm {
      height: 32px;
      padding: 0 var(--space-3);
      font-size: 13px;
    }
    .size-md {
      height: var(--height-button-site);
    }
    .size-lg {
      height: var(--height-button-phone);
      font-size: 15px;
    }
    .full-w {
      width: 100%;
    }

    .btn.primary {
      background: var(--green-action);
      color: var(--text-on-action);
      border-color: transparent;
    }
    .btn.primary:hover:not(:disabled) {
      background: var(--green-bright);
    }

    .btn.outline {
      background: var(--bg-tile);
      color: var(--green-text);
      border-color: var(--green-action);
    }
    .btn.outline:hover:not(:disabled) {
      background: var(--green-soft);
    }

    .btn.ghost {
      background: transparent;
      color: var(--text-secondary);
      border-color: transparent;
    }
    .btn.ghost:hover:not(:disabled) {
      background: var(--bg-nested);
      color: var(--text-primary);
    }

    .btn.danger {
      background: var(--danger-soft);
      color: var(--danger-text);
      border-color: transparent;
    }
    .btn.danger:hover:not(:disabled) {
      opacity: 0.85;
    }

    .btn.dark {
      background: var(--graphite-bg);
      color: var(--graphite-text);
      border-color: transparent;
    }
    .btn.dark:hover:not(:disabled) {
      opacity: 0.9;
    }

    .btn.quiet {
      background: var(--bg-tile);
      color: var(--text-primary);
      border-color: var(--line);
    }
    .btn.quiet:hover:not(:disabled) {
      background: var(--bg-nested);
    }

    .btn:disabled {
      background: var(--disabled-bg);
      color: var(--disabled-text);
      border-color: transparent;
      cursor: not-allowed;
      opacity: 0.7;
    }

    .btn-label {
      display: inline-flex;
      align-items: center;
    }
  `,
})
export class ButtonComponent {
  readonly kind = input<ButtonKind>('primary');
  readonly size = input<'sm' | 'md' | 'lg'>('md');
  readonly icon = input<IconName | null>(null);
  readonly type = input<'button' | 'submit' | 'reset'>('button');
  readonly disabled = input<boolean>(false);
  readonly disabledTitle = input<string | null>(null);
  readonly fullWidth = input<boolean>(false);

  readonly btnClick = output<MouseEvent>();

  get iconSize(): () => number {
    return () => (this.size() === 'sm' ? 16 : 18);
  }

  handleClick(ev: MouseEvent): void {
    if (!this.disabled()) {
      this.btnClick.emit(ev);
    }
  }
}
