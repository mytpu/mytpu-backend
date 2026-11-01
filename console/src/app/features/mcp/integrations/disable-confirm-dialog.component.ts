import { Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';

@Component({
  selector: 'app-disable-confirm-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, DialogComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.integrations.disableDialogTitle' | t: { name: targetName() }"
        [subtitle]="'mcp.integrations.disableDialogSubtitle' | t"
        (closed)="cancel.emit()"
      >
        <div class="dialog-content">
          <label class="reason-label">{{ 'mcp.integrations.disableReasonLabel' | t }}</label>
          <textarea
            class="reason-area"
            rows="3"
            [placeholder]="'mcp.integrations.disableReasonPlaceholder' | t"
            [ngModel]="reason()"
            (ngModelChange)="onReasonChange($event)"
          ></textarea>
          @if (error()) {
            <span class="error-text">{{ error()! | t }}</span>
          }
        </div>

        <div actions class="actions-row">
          <app-button kind="ghost" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button kind="danger" [disabled]="!reason().trim() || submitting()" (btnClick)="onSubmit()">
            {{ 'mcp.integrations.btnDisable' | t }}
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
    .dialog-content {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .reason-label {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .reason-area {
      width: 100%;
      border-radius: var(--radius-button);
      border: 1px solid var(--border-tile);
      background: var(--bg-nested);
      color: var(--text-primary);
      padding: var(--space-3);
      box-sizing: border-box;
      resize: vertical;
      font-size: 14px;
      outline: none;
    }
    .reason-area:focus {
      border-color: var(--green-action);
    }
    .error-text {
      font-size: 12px;
      color: var(--danger-text);
    }
    .actions-row {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-3);
      width: 100%;
    }
  `,
})
export class DisableConfirmDialogComponent {
  readonly targetName = input.required<string>();
  readonly submitting = input<boolean>(false);

  readonly confirm = output<string>();
  readonly cancel = output<void>();

  readonly reason = signal<string>('');
  readonly error = signal<string | null>(null);

  onReasonChange(val: string): void {
    this.reason.set(val);
    if (val.trim()) this.error.set(null);
  }

  onSubmit(): void {
    const r = this.reason().trim();
    if (!r) {
      this.error.set('mcp.integrations.reasonRequired');
      return;
    }
    this.confirm.emit(r);
  }
}
