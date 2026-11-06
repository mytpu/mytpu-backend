import { Component, input } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { AuthForm } from './connector-editor.model';

@Component({
  selector: 'app-editor-auth',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe],
  templateUrl: './editor-auth.component.html',
  styleUrl: './editor-auth.component.css',
})
export class EditorAuthComponent {
  readonly group = input.required<FormGroup<AuthForm>>();
  readonly submitted = input<boolean>(false);
}
