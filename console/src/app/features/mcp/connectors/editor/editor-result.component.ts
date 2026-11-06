import { Component, input, output } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { SelectComponent } from '../../../../ui/select/select.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { createFieldGroup, ResultForm } from './connector-editor.model';
import { EditorFieldRowComponent } from './editor-field-row.component';

@Component({
  selector: 'app-editor-result',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TPipe,
    SelectComponent,
    ButtonComponent,
    EditorFieldRowComponent,
  ],
  templateUrl: './editor-result.component.html',
  styleUrl: './editor-result.component.css',
})
export class EditorResultComponent {
  readonly group = input.required<FormGroup<ResultForm>>();
  readonly submitted = input<boolean>(false);
  readonly canChange = input<boolean>(false);

  readonly changed = output<void>();

  addField(): void {
    this.group().controls.fields.push(createFieldGroup());
    this.changed.emit();
  }

  removeField(idx: number): void {
    this.group().controls.fields.removeAt(idx);
    this.changed.emit();
  }
}
