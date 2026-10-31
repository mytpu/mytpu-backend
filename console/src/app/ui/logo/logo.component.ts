import { Component, input } from '@angular/core';

@Component({
  selector: 'app-logo',
  standalone: true,
  template: `
    <div class="logo-wrapper">
      <div
        class="logo-mark"
        [style.width.px]="size()"
        [style.height.px]="size()"
      >
        <span class="cell corner"></span>
        <span class="cell cross"></span>
        <span class="cell corner"></span>
        <span class="cell cross"></span>
        <span class="cell cross"></span>
        <span class="cell cross"></span>
        <span class="cell corner"></span>
        <span class="cell cross"></span>
        <span class="cell corner"></span>
      </div>
      @if (title()) {
        <div class="logo-text">
          <div class="title">{{ title() }}</div>
          @if (subtitle()) {
            <div class="subtitle">{{ subtitle() }}</div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    .logo-wrapper {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
    }
    .logo-mark {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      grid-template-rows: repeat(3, 1fr);
      gap: 2px;
      flex-shrink: 0;
    }
    .cell {
      border-radius: var(--logo-radius);
      display: block;
    }
    .cell.corner {
      background: var(--logo-corner);
    }
    .cell.cross {
      background: var(--logo-cross);
    }
    .logo-text {
      display: flex;
      flex-direction: column;
      line-height: 1.2;
    }
    .title {
      font-size: 16px;
      font-weight: 800;
      color: var(--text-primary);
      letter-spacing: -0.2px;
    }
    .subtitle {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-secondary);
    }
  `,
})
export class LogoComponent {
  readonly size = input<number>(28);
  readonly title = input<string | null>(null);
  readonly subtitle = input<string | null>(null);
}
