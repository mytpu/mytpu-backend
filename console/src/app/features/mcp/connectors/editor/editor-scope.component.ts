import { Component, input } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { ScopeForm } from './connector-editor.model';

@Component({
  selector: 'app-editor-scope',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe],
  templateUrl: './editor-scope.component.html',
  styleUrl: './editor-scope.component.css',
})
export class EditorScopeComponent {
  readonly group = input.required<FormGroup<ScopeForm>>();
  readonly submitted = input<boolean>(false);

  hasError(ctrl: AbstractControl): boolean {
    return ctrl.invalid && (ctrl.touched || ctrl.dirty || this.submitted());
  }
}
