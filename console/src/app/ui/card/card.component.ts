import { Component, input, output } from '@angular/core';
import { IconComponent, IconName } from '../icon/icon.component';

@Component({
  selector: 'app-card',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="card">
      @if (label() || link()) {
        <div class="card-head">
          <div class="label-group">
            @if (icon()) {
              <app-icon [name]="icon()!" [size]="16" class="head-icon" />
            }
            @if (label()) {
              <span class="head-label">{{ label() }}</span>
            }
          </div>
          @if (link()) {
            <a class="head-link" (click)="linkClick.emit($event)">{{ link() }}</a>
          }
        </div>
      }
      <div class="card-body">
        <ng-content />
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .card {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      padding: 18px 20px;
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      box-sizing: border-box;
    }
    .card-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
    }
    .label-group {
      display: inline-flex;
      align-items: center;
      gap: 7px;
    }
    .head-icon {
      color: var(--text-secondary);
    }
    .head-label {
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--text-secondary);
    }
    .head-link {
      font-size: 13px;
      font-weight: 700;
      color: var(--green-text);
      cursor: pointer;
      text-decoration: none;
    }
    .head-link:hover {
      text-decoration: underline;
    }
    .card-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
  `,
})
export class CardComponent {
  readonly label = input<string | null>(null);
  readonly icon = input<IconName | null>(null);
  readonly link = input<string | null>(null);

  readonly linkClick = output<MouseEvent>();
}
