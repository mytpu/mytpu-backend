import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { ButtonComponent } from '../../../ui/button/button.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';

@Component({
  selector: 'app-resume-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, ToggleComponent, DialogComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.keys.resumeDialogTitle' | t"
        [subtitle]="'mcp.keys.resumeDialogSubtitle' | t: { owner: owner() }"
        (closed)="cancel.emit()"
      >
        <div class="resume-body">
          <div class="toggle-row">
            <span class="toggle-label">{{ 'mcp.keys.resumeGeoAnyLabel' | t }}</span>
            <app-toggle [checked]="allowAny()" (changed)="onToggleChanged($event)" />
          </div>

          @if (allowAny()) {
            <div class="field-col">
              <label class="label">{{ 'mcp.keys.resumeGeoReasonLabel' | t }}</label>
              <textarea
                class="reason-area"
                rows="3"
                [placeholder]="'mcp.keys.resumeGeoReasonPlaceholder' | t"
                [ngModel]="reason()"
                (ngModelChange)="reason.set($event)"
              ></textarea>
              @if (validationError()) {
                <span class="error-msg">{{ validationError() }}</span>
              }
            </div>
          }
        </div>

        <div actions class="actions-row">
          <app-button kind="ghost" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button kind="primary" [disabled]="submitting()" (btnClick)="onSubmit()">
            {{ 'mcp.keys.resumeSubmit' | t }}
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
    .resume-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .toggle-label {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary);
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
    }
    .reason-area:focus {
      border-color: var(--green-action);
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
export class ResumeDialogComponent {
  private readonly i18n = inject(I18n);

  readonly owner = input.required<string>();
  readonly submitting = input<boolean>(false);

  readonly allowAny = signal<boolean>(false);
  readonly reason = signal<string>('');
  readonly validationError = signal<string | null>(null);

  readonly submitResume = output<{ geo?: string; geoReason?: string }>();
  readonly cancel = output<void>();

  onToggleChanged(val: boolean): void {
    this.allowAny.set(val);
    if (!val) {
      this.validationError.set(null);
    }
  }

  onSubmit(): void {
    if (this.allowAny() && !this.reason().trim()) {
      this.validationError.set(this.i18n.t('mcp.wizard.errorGeoReasonRequired'));
      return;
    }
    this.submitResume.emit({
      geo: this.allowAny() ? 'any' : 'ru_only',
      geoReason: this.allowAny() ? this.reason().trim() : undefined,
    });
  }
}
