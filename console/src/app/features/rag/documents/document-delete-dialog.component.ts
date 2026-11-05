import { Component, input, output } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { DocumentRow } from './documents.model';

@Component({
  selector: 'app-document-delete-dialog',
  standalone: true,
  imports: [TPipe, ButtonComponent, DialogComponent],
  template: `
    <div class="modal-overlay">
      <app-dialog
        [title]="'rag.documents.deleteDialogTitle' | t"
        (closed)="cancel.emit()"
      >
        <div class="dialog-body">
          <div class="doc-card">
            <span class="doc-title">{{ doc().title }}</span>
            <span class="doc-meta">
              {{ doc().url }} · {{ doc().sections }} {{ 'rag.common.sections' | t }} · {{ doc().chunks }} {{ 'rag.common.chunks' | t }}
            </span>
          </div>
          <p class="dialog-desc">{{ 'rag.documents.deleteDialogDesc' | t }}</p>
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
    .doc-card {
      background: var(--bg-nested);
      border-radius: var(--radius-tile);
      padding: var(--space-3);
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .doc-title {
      font-weight: 700;
      font-size: 14px;
      color: var(--text-primary);
    }
    .doc-meta {
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
export class DocumentDeleteDialogComponent {
  readonly doc = input.required<DocumentRow>();
  readonly cancel = output<void>();
  readonly confirm = output<void>();
}
