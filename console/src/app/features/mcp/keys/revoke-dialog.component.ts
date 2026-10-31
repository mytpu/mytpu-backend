import { Component, input, output } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';

@Component({
  selector: 'app-revoke-dialog',
  standalone: true,
  imports: [TPipe, ButtonComponent, DialogComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.keys.revokeDialogTitle' | t"
        [subtitle]="'mcp.keys.revokeDialogSubtitle' | t: { owner: owner(), prefix: keyPrefix() }"
        (closed)="cancel.emit()"
      >
        <div actions class="actions-row">
          <app-button kind="ghost" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button kind="danger" [disabled]="submitting()" (btnClick)="confirm.emit()">
            {{ 'mcp.keys.revokeSubmit' | t }}
          </app-button>
        </div>
      </app-dialog>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .overlay {
      position: fixed;
      inset: 0;
      background: var(--bg-overlay);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 999;
      padding: var(--space-4);
      box-sizing: border-box;
    }
    .actions-row {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-2);
      width: 100%;
    }
  `,
})
export class RevokeDialogComponent {
  readonly owner = input.required<string>();
  readonly keyPrefix = input.required<string>();
  readonly submitting = input<boolean>(false);

  readonly confirm = output<void>();
  readonly cancel = output<void>();
}
