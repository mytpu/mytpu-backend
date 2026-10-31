import { Component, input } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';

@Component({
  selector: 'app-step-owner',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe, ToggleComponent],
  templateUrl: './step-owner.component.html',
  styleUrl: './step-owner.component.css',
})
export class StepOwnerComponent {
  readonly form = input.required<FormGroup>();
  readonly maxDays = input.required<number>();
  readonly fieldErrors = input.required<Record<string, string>>();
}
