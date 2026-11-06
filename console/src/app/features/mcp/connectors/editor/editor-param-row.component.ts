import { Component, input, output } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { ParamForm } from './connector-editor.model';

@Component({
  selector: 'app-editor-param-row',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe, ButtonComponent],
  template: `
    <div [formGroup]="group()" class="param-row">
      <div class="row-cell col-name">
        <input
          type="text"
          class="row-input"
          placeholder="name"
          formControlName="name"
          (input)="changed.emit()"
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

      <div class="row-cell col-in">
        <select class="row-select" formControlName="in" (change)="changed.emit()">
          <option value="query">{{ 'connectors.editor.paramInQuery' | t }}</option>
          <option value="path">{{ 'connectors.editor.paramInPath' | t }}</option>
        </select>
      </div>

      <div class="row-cell col-type">
        <select class="row-select" formControlName="type">
          <option value="string">string</option>
          <option value="integer">integer</option>
          <option value="number">number</option>
          <option value="boolean">boolean</option>
        </select>
      </div>

      <div class="row-cell col-req">
        <label class="check-label">
          <input type="checkbox" formControlName="required" />
          <span>{{ 'connectors.paramRequiredYes' | t }}</span>
        </label>
      </div>

      <div class="row-cell col-def">
        <input
          type="text"
          class="row-input"
          [placeholder]="'connectors.paramColDefault' | t"
          formControlName="default"
        />
      </div>

      <div class="row-cell col-desc">
        <input
          type="text"
          class="row-input"
          [placeholder]="'connectors.paramColDesc' | t"
          formControlName="description"
        />
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
    .param-row {
      display: grid;
      grid-template-columns: 140px 110px 100px 70px 100px 1fr 40px;
      gap: var(--space-2);
      align-items: start;
      margin-bottom: var(--space-2);
    }
    @media (max-width: 900px) {
      .param-row { grid-template-columns: 1fr; }
    }
    .row-cell { display: flex; flex-direction: column; gap: 2px; }
    .row-input, .row-select {
      height: 36px;
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      background: var(--bg-tile);
      color: var(--text-primary);
      padding: 0 var(--space-2);
      font-size: 13px;
      outline: none;
      box-sizing: border-box;
      width: 100%;
    }
    .row-input:focus, .row-select:focus { border-color: var(--green-action); }
    .row-input.has-error { border-color: var(--danger-text); }
    .err-text { font-size: 11px; color: var(--danger-text); line-height: 1.2; }
    .check-label {
      display: flex;
      align-items: center;
      gap: 6px;
      height: 36px;
      font-size: 13px;
      color: var(--text-secondary);
      cursor: pointer;
    }
    .row-actions { display: flex; align-items: center; padding-top: 2px; }
  `,
})
export class EditorParamRowComponent {
  readonly group = input.required<FormGroup<ParamForm>>();
  readonly index = input.required<number>();
  readonly submitted = input<boolean>(false);
  readonly canChange = input<boolean>(true);

  readonly remove = output<number>();
  readonly changed = output<void>();

  hasError(ctrl: AbstractControl): boolean {
    return ctrl.invalid && (ctrl.touched || ctrl.dirty || this.submitted());
  }
}
