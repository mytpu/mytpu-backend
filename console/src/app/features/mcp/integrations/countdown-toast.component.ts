import {
  Component,
  DestroyRef,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';

@Component({
  selector: 'app-countdown-toast',
  standalone: true,
  imports: [TPipe, ButtonComponent, IconComponent],
  template: `
    <div class="toast" role="alert">
      <div class="toast-left">
        <app-icon name="toggleOff" [size]="20" class="toast-icon" />
        <span class="toast-text">
          @if (secondsRemaining() > 0) {
            {{ 'mcp.integrations.toastDisabled' | t: { name: itemName(), sec: secondsRemaining() } }}
          } @else {
            {{ 'mcp.integrations.toastDisabledDone' | t: { name: itemName() } }}
          }
        </span>
      </div>
      <div class="toast-actions">
        @if (canUndo()) {
          <app-button kind="primary" (btnClick)="undo.emit()">
            {{ 'mcp.integrations.btnCancel' | t }}
          </app-button>
        }
        <button type="button" class="close-btn" (click)="dismiss.emit()" [attr.aria-label]="'mcp.integrations.close' | t">
          <app-icon name="x" [size]="16" />
        </button>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      position: fixed;
      bottom: var(--space-6);
      left: calc(248px + var(--space-6));
      z-index: 900;
    }
    .toast {
      background: var(--graphite-bg);
      color: var(--graphite-text);
      display: flex;
      align-items: center;
      gap: var(--space-4);
      padding: 12px 14px 12px 18px;
      border-radius: var(--radius-card);
      box-shadow: var(--shadow-dialog);
    }
    .toast-left {
      display: flex;
      align-items: center;
      gap: var(--space-3);
    }
    .toast-icon {
      color: var(--graphite-text);
    }
    .toast-text {
      font-size: 14px;
      font-weight: 500;
    }
    .toast-actions {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }
    .close-btn {
      background: transparent;
      border: none;
      color: var(--graphite-text);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 4px;
      border-radius: var(--radius-button);
      opacity: 0.8;
    }
    .close-btn:hover {
      opacity: 1;
    }
  `,
})
export class CountdownToastComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);

  readonly itemName = input.required<string>();
  readonly canUndo = input<boolean>(true);

  readonly undo = output<void>();
  readonly dismiss = output<void>();

  readonly secondsRemaining = signal<number>(30);

  ngOnInit(): void {
    const timer = setInterval(() => {
      const cur = this.secondsRemaining();
      if (cur <= 1) {
        this.secondsRemaining.set(0);
        clearInterval(timer);
      } else {
        this.secondsRemaining.set(cur - 1);
      }
    }, 1000);

    this.destroyRef.onDestroy(() => clearInterval(timer));
  }
}
