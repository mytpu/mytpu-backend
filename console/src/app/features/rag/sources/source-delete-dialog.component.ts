import { Component, input, output } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { SourceItem } from './sources.model';

@Component({
  selector: 'app-source-delete-dialog',
  standalone: true,
  imports: [TPipe, ButtonComponent, DialogComponent],
  template: `
    <div class="modal-overlay">
      <app-dialog
        [title]="'rag.sources.deleteConfirmTitle' | t"
        (closed)="cancel.emit()"
      >
        <div class="dialog-body">
          <div class="source-card">
            <span class="source-name">{{ source().title }}</span>
            <span class="source-sub">{{ source().address }} · {{ source().documents }} {{ 'rag.common.documents' | t }}</span>
          </div>
          <p class="dialog-desc">{{ 'rag.sources.deleteConfirmDesc' | t }}</p>
        </div>

        <div actions class="actions-row">
          <app-button kind="quiet" (btnClick)="cancel.emit()">
            {{ 'rag.common.cancel' | t }}
          </app-button>
          <app-button kind="danger" icon="trash" (btnClick)="confirm.emit()">
            {{ 'rag.common.delete' | t }}
          </app-button>
        </div>
      </app-dialog>
    </div>
  `,
  styles: `
    .modal-overlay {
      position: fixed;
      inset: 0;
      background: var(--bg-overlay);
      z-index: 1000;
      display: flex;
      align-items: center;
      justify-content: center;
      backdrop-filter: blur(2px);
    }
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      padding: var(--space-4) 0;
    }
    .source-card {
      background: var(--bg-nested);
      border-radius: var(--radius-tile);
      padding: var(--space-3);
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .source-name {
      font-weight: 700;
      font-size: 14px;
      color: var(--text-primary);
    }
    .source-sub {
      font-size: 12px;
      color: var(--text-secondary);
    }
    .dialog-desc {
      font-size: 14px;
      color: var(--text-secondary);
      margin: 0;
      line-height: 1.4;
    }
    .actions-row {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-2);
      width: 100%;
    }
  `,
})
export class SourceDeleteDialogComponent {
  readonly source = input.required<SourceItem>();
  readonly cancel = output<void>();
  readonly confirm = output<void>();
}
