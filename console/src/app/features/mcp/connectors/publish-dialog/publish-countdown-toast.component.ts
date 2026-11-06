import {
  Component,
  DestroyRef,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { IconComponent } from '../../../../ui/icon/icon.component';

@Component({
  selector: 'app-publish-countdown-toast',
  standalone: true,
  imports: [TPipe, IconComponent],
  template: `
    <div class="toast animate-enter" role="alert">
      <div class="toast-left">
        <app-icon name="check" [size]="20" class="toast-icon" />
        <span class="toast-text">
          @if (secondsRemaining() > 0) {
            {{ 'connectors.toastPublishCountdown' | t: { version: version(), sec: secondsRemaining() } }}
          } @else {
            {{ 'connectors.toastPublishDone' | t: { version: version() } }}
          }
        </span>
      </div>
      <button
        type="button"
        class="close-btn"
        (click)="dismiss.emit()"
        [attr.aria-label]="'connectors.closeToast' | t"
      >
        <app-icon name="x" [size]="16" />
      </button>
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
    .animate-enter {
      animation: toastSlideUp var(--dur-base) var(--ease);
    }
    @keyframes toastSlideUp {
      from {
        opacity: 0;
        transform: translateY(12px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .animate-enter {
        animation: none;
      }
    }
  `,
})
export class PublishCountdownToastComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);

  readonly version = input.required<number>();
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
