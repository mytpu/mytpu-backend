import { Component, input, output } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { FieldForm } from './connector-editor.model';

@Component({
  selector: 'app-editor-field-row',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe, ButtonComponent],
  template: `
    <div [formGroup]="group()" class="field-row">
      <div class="row-cell col-name">
        <input
          type="text"
          class="row-input"
          [placeholder]="'connectors.editor.fieldNamePlaceholder' | t"
          formControlName="name"
          [class.has-error]="hasError(group().controls.name)"
        />
        @if (hasError(group().controls.name)) {
          <span class="err-text">
            @if (group().controls.name.hasError('required')) {
              {{ 'connectors.editor.errorRequired' | t }}
            } @else if (group().controls.name.hasError('identifier')) {
              {{ 'connectors.editor.errorIdentifier' | t }}
            }
          </span>
        }
      </div>

      <div class="row-cell col-pointer">
        <input
          type="text"
          class="row-input mono"
          [placeholder]="'connectors.editor.fieldPointerPlaceholder' | t"
          formControlName="pointer"
          [class.has-error]="hasError(group().controls.pointer)"
        />
        @if (hasError(group().controls.pointer)) {
          <span class="err-text">{{ 'connectors.editor.errorRequired' | t }}</span>
        }
      </div>

      <div class="row-cell col-label">
        <input
          type="text"
          class="row-input"
          [placeholder]="'connectors.editor.fieldLabelPlaceholder' | t"
          formControlName="label"
          [class.has-error]="hasError(group().controls.label)"
        />
        @if (hasError(group().controls.label)) {
          <span class="err-text">{{ 'connectors.editor.errorRequired' | t }}</span>
        }
      </div>

      <div class="row-actions">
        <app-button
          kind="ghost"
          size="sm"
          icon="trash"
          [disabled]="!canChange()"
          (btnClick)="remove.emit(index())"
        />
      </div>
    </div>
  `,
  styles: `
    :host { display: block; width: 100%; }
    .field-row {
      display: grid;
      grid-template-columns: 180px 180px 1fr 40px;
      gap: var(--space-2);
      align-items: start;
      margin-bottom: var(--space-2);
    }
    @media (max-width: 900px) {
      .field-row { grid-template-columns: 1fr; }
    }
    .row-cell { display: flex; flex-direction: column; gap: 2px; }
    .row-input {
      height: 36px;
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      background: var(--bg-tile);
      color: var(--text-primary);
      padding: 0 var(--space-3);
      font-size: 13px;
      outline: none;
      box-sizing: border-box;
      width: 100%;
    }
    .row-input:focus { border-color: var(--green-action); }
    .row-input.mono { font-family: var(--font-mono); font-size: 12px; }
    .row-input.has-error { border-color: var(--danger-text); }
    .err-text { font-size: 11px; color: var(--danger-text); line-height: 1.2; }
    .row-actions { display: flex; align-items: center; padding-top: 2px; }
  `,
})
export class EditorFieldRowComponent {
  readonly group = input.required<FormGroup<FieldForm>>();
  readonly index = input.required<number>();
  readonly submitted = input<boolean>(false);
  readonly canChange = input<boolean>(false);

  readonly remove = output<number>();

  hasError(ctrl: AbstractControl): boolean {
    return ctrl.invalid && (ctrl.touched || ctrl.dirty || this.submitted());
  }
}
