import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18n } from '../../../core/i18n/i18n.service';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { IconComponent } from '../../../ui/icon/icon.component';

@Component({
  selector: 'app-mode-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, DialogComponent, IconComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.mode.dialogTitle' | t: { modeName: modeTitle() }"
        [subtitle]="'mcp.mode.dialogSubtitle' | t: { mode: targetMode() }"
        (closed)="cancel.emit()"
      >
        <div class="dialog-body">
          <div class="note-box">
            <app-icon name="alert" [size]="18" class="note-icon" />
            <span class="note-text">{{ noteText() | t }}</span>
          </div>

          <div class="field-col">
            <label class="field-label">{{ 'mcp.mode.reasonLabel' | t }}</label>
            <textarea
              class="reason-area"
              rows="3"
              [placeholder]="'mcp.mode.reasonPlaceholder' | t"
              [ngModel]="reason()"
              (ngModelChange)="onReasonChange($event)"
            ></textarea>
            @if (error()) {
              <span animate.enter="m-reveal-enter" class="error-msg">{{ error() }}</span>
            }
          </div>
        </div>

        <div actions class="actions-row">
          <app-button kind="quiet" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button kind="primary" [disabled]="!reason().trim() || submitting()" (btnClick)="onSubmit()">
            {{ 'mcp.mode.dialogBtnConfirm' | t }}
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
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }
    .note-box {
      background: var(--warn-soft);
      color: var(--warn-text);
      border-radius: var(--radius-button);
      padding: 12px 14px;
      display: flex;
      align-items: flex-start;
      gap: var(--space-3);
    }
    .note-icon {
      color: var(--warn-icon);
      margin-top: 2px;
      flex-shrink: 0;
    }
    .note-text {
      font-size: 13px;
      line-height: 1.4;
    }
    .field-col {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .field-label {
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
      padding: 10px 12px;
      font-size: 14px;
      resize: vertical;
      box-sizing: border-box;
      outline: none;
    }
    .reason-area:focus {
      border-color: var(--green-action);
    }
    .error-msg {
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
export class ModeDialogComponent {
  private readonly i18n = inject(I18n);

  readonly targetMode = input.required<string>();
  readonly initialReason = input<string>('');
  readonly submitting = input<boolean>(false);

  readonly confirm = output<string>();
  readonly cancel = output<void>();

  // Причина, введённая на странице, переносится в окно (входы известны только после конструктора)
  readonly reason = linkedSignal(() => this.initialReason());
  readonly error = signal<string | null>(null);

  readonly modeTitle = computed(() => {
    switch (this.targetMode()) {
      case 'read_only': return this.i18n.t('mcp.mode.readOnlyTitle');
      case 'internal_only': return this.i18n.t('mcp.mode.internalOnlyTitle');
      default: return this.i18n.t('mcp.mode.normalTitle');
    }
  });

  readonly noteText = computed(() => {
    switch (this.targetMode()) {
      case 'read_only': return 'mcp.mode.dialogNoteReadOnly';
      case 'internal_only': return 'mcp.mode.dialogNoteInternalOnly';
      default: return 'mcp.mode.dialogNoteNormal';
    }
  });

  onReasonChange(val: string): void {
    this.reason.set(val);
    if (val.trim()) this.error.set(null);
  }

  onSubmit(): void {
    const r = this.reason().trim();
    if (!r) {
      this.error.set(this.i18n.t('mcp.mode.errorReasonRequired'));
      return;
    }
    this.confirm.emit(r);
  }
}
