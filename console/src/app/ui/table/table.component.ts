import { Component, input } from '@angular/core';

export interface TableColumn {
  title: string;
  width?: string;
  align?: 'left' | 'center' | 'right';
}

@Component({
  selector: 'app-table',
  standalone: true,
  template: `
    <div class="table-card">
      <div class="table-scroll">
        <table class="data-table">
          @if (columns().length > 0) {
            <thead>
              <tr>
                @for (col of columns(); track col.title) {
                  <th
                    [style.width]="col.width || 'auto'"
                    [class]="'text-' + (col.align || 'left')"
                  >
                    {{ col.title }}
                  </th>
                }
              </tr>
            </thead>
          }
          <tbody>
            <ng-content />
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .table-card {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      overflow: hidden;
    }
    .table-scroll {
      width: 100%;
      overflow-x: auto;
    }
    .data-table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
      font-size: 14px;
      line-height: 1.4;
    }
    thead th {
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.6px;
      color: var(--text-muted);
      padding: 12px 16px;
      border-bottom: 1px solid var(--line);
      white-space: nowrap;
      background: var(--bg-tile);
    }
    :host ::ng-deep tbody tr {
      border-bottom: 1px solid var(--line);
      transition: background-color var(--dur-fast) var(--ease);
    }
    :host ::ng-deep tbody tr:last-child {
      border-bottom: none;
    }
    :host ::ng-deep tbody tr:hover {
      background-color: var(--bg-nested);
    }
    :host ::ng-deep tbody td {
      padding: 12px 16px;
      color: var(--text-primary);
      vertical-align: middle;
    }
    .text-left {
      text-align: left;
    }
    .text-center {
      text-align: center;
    }
    .text-right {
      text-align: right;
    }
  `,
})
export class TableComponent {
  readonly columns = input<TableColumn[]>([]);
}
