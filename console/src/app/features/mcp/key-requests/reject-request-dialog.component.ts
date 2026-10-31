import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';

@Component({
  selector: 'app-reject-request-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, DialogComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.requests.dialogRejectTitle' | t"
        [subtitle]="'mcp.requests.dialogRejectSubtitle' | t: { requester: requester() }"
        (closed)="cancel.emit()"
      >
        <div class="field-col">
          <label class="label">{{ 'mcp.requests.rejectReasonLabel' | t }}</label>
          <textarea
            class="reason-area"
            rows="4"
            [placeholder]="'mcp.requests.rejectReasonPlaceholder' | t"
            [ngModel]="reason()"
            (ngModelChange)="onReasonChange($event)"
          ></textarea>
          @if (error()) {
            <span class="error-msg">{{ error() }}</span>
          }
        </div>

        <div actions class="actions-row">
          <app-button kind="ghost" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button
            kind="danger"
            [disabled]="submitting() || !reason().trim()"
            (btnClick)="onSubmit()"
          >
            {{ 'mcp.requests.btnConfirmReject' | t }}
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
    .field-col {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .label {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .reason-area {
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      background: var(--bg-tile);
      color: var(--text-primary);
      padding: var(--space-2) var(--space-3);
      font-family: var(--font-sans);
      font-size: 13px;
      outline: none;
      resize: vertical;
      box-sizing: border-box;
    }
    .reason-area:focus {
      border-color: var(--danger-text);
    }
    .error-msg {
      font-size: 12px;
      color: var(--danger-text);
      font-weight: 500;
    }
    .actions-row {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-2);
      width: 100%;
    }
  `,
})
export class RejectRequestDialogComponent {
  private readonly i18n = inject(I18n);

  readonly requester = input.required<string>();
  readonly submitting = input<boolean>(false);

  readonly reason = signal<string>('');
  readonly error = signal<string | null>(null);

  readonly confirmReject = output<string>();
  readonly cancel = output<void>();

  onReasonChange(val: string): void {
    this.reason.set(val);
    if (this.error()) {
      this.error.set(null);
    }
  }

  onSubmit(): void {
    const trimmed = this.reason().trim();
    if (!trimmed) {
      this.error.set(this.i18n.t('mcp.requests.errorReasonRequired'));
      return;
    }
    if (trimmed.length > 500) {
      this.error.set(this.i18n.t('mcp.requests.errorReasonLength'));
      return;
    }
    this.confirmReject.emit(trimmed);
  }
}
