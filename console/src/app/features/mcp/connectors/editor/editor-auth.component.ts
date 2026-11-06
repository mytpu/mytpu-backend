import { Component, input } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { SelectComponent } from '../../../../ui/select/select.component';
import { AuthForm } from './connector-editor.model';

@Component({
  selector: 'app-editor-auth',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe, SelectComponent],
  templateUrl: './editor-auth.component.html',
  styleUrl: './editor-auth.component.css',
})
export class EditorAuthComponent {
  readonly group = input.required<FormGroup<AuthForm>>();
  readonly submitted = input<boolean>(false);
}
