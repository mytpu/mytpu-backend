import { Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';

@Component({
  selector: 'app-reissue-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, DialogComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.keys.reissueDialogTitle' | t"
        [subtitle]="'mcp.keys.reissueDialogSubtitle' | t"
        (closed)="cancel.emit()"
      >
        <div class="field-row">
          <label class="label">{{ 'mcp.keys.reissueGraceLabel' | t }}</label>
          <input
            type="number"
            min="0"
            max="168"
            class="num-input"
            [ngModel]="graceHours()"
            (ngModelChange)="onGraceChange($event)"
          />
        </div>

        <div actions class="actions-row">
          <app-button kind="ghost" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button kind="primary" [disabled]="submitting()" (btnClick)="onSubmit()">
            {{ 'mcp.keys.reissueSubmit' | t }}
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
    .field-row {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .label {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .num-input {
      height: var(--height-field);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      background: var(--bg-tile);
      color: var(--text-primary);
      padding: 0 var(--space-3);
      font-family: var(--font-sans);
      font-size: 14px;
      outline: none;
      transition: border-color var(--dur-fast) var(--ease);
    }
    .num-input:focus {
      border-color: var(--green-action);
    }
    .actions-row {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-2);
      width: 100%;
    }
  `,
})
export class ReissueDialogComponent {
  readonly submitting = input<boolean>(false);
  readonly graceHours = signal<number>(24);

  readonly submitReissue = output<number>();
  readonly cancel = output<void>();

  onGraceChange(val: number): void {
    const num = Math.max(0, Math.min(168, Number(val) || 0));
    this.graceHours.set(num);
  }

  onSubmit(): void {
    this.submitReissue.emit(this.graceHours());
  }
}
