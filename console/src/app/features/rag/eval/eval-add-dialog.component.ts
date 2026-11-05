import { Component, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { EvalQuestionItem } from './eval.model';

@Component({
  selector: 'app-eval-add-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, DialogComponent, ToggleComponent],
  template: `
    <div class="modal-overlay">
      <app-dialog
        [title]="'rag.eval.addQuestionDialogTitle' | t"
        (closed)="cancel.emit()"
      >
        <div class="dialog-body">
          <div class="field-col">
            <label class="field-label">{{ 'rag.eval.questionTextLabel' | t }}</label>
            <input
              type="text"
              class="text-input"
              [placeholder]="'rag.eval.questionTextPlaceholder' | t"
              [ngModel]="question()"
              (ngModelChange)="question.set($event)"
            />
          </div>

          <div class="field-col">
            <label class="field-label">{{ 'rag.eval.expectedUrlsLabel' | t }}</label>
            <textarea
              class="text-area"
              rows="3"
              placeholder="https://tpu.ru/university/stipendii&#10;upload:3/polozhenie.pdf"
              [ngModel]="urlsText()"
              (ngModelChange)="urlsText.set($event)"
            ></textarea>
          </div>

          <div class="toggle-row">
            <span class="toggle-label">{{ 'rag.eval.hasAnswerLabel' | t }}</span>
            <app-toggle
              [checked]="hasAnswer()"
              (changed)="hasAnswer.set($event)"
            />
          </div>

          <div class="field-col">
            <label class="field-label">{{ 'rag.eval.noteLabel' | t }}</label>
            <input
              type="text"
              class="text-input"
              [placeholder]="'rag.eval.notePlaceholder' | t"
              [ngModel]="note()"
              (ngModelChange)="note.set($event)"
            />
          </div>
        </div>

        <div actions class="actions-row">
          <app-button kind="quiet" (btnClick)="cancel.emit()">
            {{ 'rag.common.cancel' | t }}
          </app-button>
          <app-button
            kind="primary"
            [disabled]="!question().trim()"
            (btnClick)="submit()"
          >
            {{ 'rag.common.save' | t }}
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
    .field-col {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .field-label {
      font-size: 12px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .text-input {
      height: var(--height-field);
      border: 1px solid var(--line);
      border-radius: var(--radius-button);
      background: var(--bg-page);
      padding: 0 var(--space-3);
      font-size: 14px;
      color: var(--text-primary);
      outline: none;
    }
    .text-input:focus {
      border-color: var(--green-action);
    }
    .text-area {
      border: 1px solid var(--line);
      border-radius: var(--radius-button);
      background: var(--bg-page);
      padding: var(--space-2) var(--space-3);
      font-size: 13px;
      font-family: var(--font-mono);
      color: var(--text-primary);
      outline: none;
      resize: vertical;
    }
    .text-area:focus {
      border-color: var(--green-action);
    }
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      padding: var(--space-2) 0;
    }
    .toggle-label {
      font-size: 14px;
      font-weight: 500;
      color: var(--text-primary);
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
export class EvalAddDialogComponent {
  readonly cancel = output<void>();
  readonly save = output<EvalQuestionItem>();

  readonly question = signal<string>('');
  readonly urlsText = signal<string>('');
  readonly hasAnswer = signal<boolean>(true);
  readonly note = signal<string>('');

  submit(): void {
    const q = this.question().trim();
    if (!q) return;
    const urls = this.urlsText()
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean);
    this.save.emit({
      question: q,
      expectedUrls: urls,
      hasAnswer: this.hasAnswer(),
      note: this.note().trim() || null,
    });
  }
}
