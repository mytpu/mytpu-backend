import {
  Component,
  effect,
  input,
  signal,
} from '@angular/core';

@Component({
  selector: 'app-live-num',
  standalone: true,
  template: `
    <span class="live-val" [class.highlighted]="highlighted()">
      {{ value() }}
    </span>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    .live-val {
      display: inline-block;
      transition: transform var(--dur-slow) var(--ease),
                  opacity var(--dur-slow) var(--ease);
    }
    .live-val.highlighted {
      transform: scale(1.08);
      opacity: 0.8;
    }
    @media (prefers-reduced-motion: reduce) {
      .live-val {
        transition: none;
        transform: none;
      }
    }
  `,
})
export class LiveNumComponent {
  readonly value = input.required<string | number>();
  readonly highlighted = signal<boolean>(false);

  private lastVal: string | number | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      const current = this.value();
      if (this.lastVal !== null && this.lastVal !== current) {
        this.triggerHighlight();
      }
      this.lastVal = current;
    });
  }

  private triggerHighlight(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.highlighted.set(true);
    this.timer = setTimeout(() => {
      this.highlighted.set(false);
      this.timer = null;
    }, 1000);
  }
}
