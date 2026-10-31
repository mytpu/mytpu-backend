import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { validateCidr } from '../key-wizard/key-wizard.model';
import { CreateKeyRequestBody } from './key-requests.model';

const DEFAULT_SCOPES = ['rasp', 'docs', 'lk', 'lms', 'mail', 'help'];

@Component({
  selector: 'app-create-request-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe, ButtonComponent, DialogComponent],
  templateUrl: './create-request-dialog.component.html',
  styleUrl: './create-request-dialog.component.css',
})
export class CreateRequestDialogComponent {
  private readonly i18n = inject(I18n);

  readonly submitting = input<boolean>(false);
  readonly availableScopes = DEFAULT_SCOPES;
  readonly selectedScopes = signal<string[]>(['rasp', 'docs']);

  readonly submitRequest = output<CreateKeyRequestBody>();
  readonly cancel = output<void>();

  readonly form = new FormGroup({
    requester: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    contact: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email],
    }),
    purpose: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    kind: new FormControl<'person' | 'server' | 'demo'>('server', { nonNullable: true }),
    days: new FormControl(90, { nonNullable: true, validators: [Validators.required, Validators.min(1)] }),
    networks: new FormControl('10.2.0.0/24', { nonNullable: true }),
  });

  toggleScope(scope: string): void {
    const cur = this.selectedScopes();
    if (cur.includes(scope)) {
      this.selectedScopes.set(cur.filter((s) => s !== scope));
    } else {
      this.selectedScopes.set([...cur, scope]);
    }
  }

  fieldError(name: string): string | null {
    const c = this.form.get(name);
    if (!c || !c.touched || !c.errors) return null;
    if (c.errors['required']) return this.i18n.t('mcp.requests.errorPurposeRequired');
    if (c.errors['email']) return this.i18n.t('mcp.requests.errorEmailInvalid');
    return null;
  }

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const val = this.form.getRawValue();
    const nets = val.networks.split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    for (const net of nets) {
      const chk = validateCidr(net);
      if (!chk.valid) {
        this.form.controls.networks.setErrors({ invalid: true });
        return;
      }
    }
    this.submitRequest.emit({
      requester: val.requester.trim(),
      contact: val.contact.trim(),
      purpose: val.purpose.trim(),
      scopes: this.selectedScopes(),
      kind: val.kind,
      days: val.days,
      networks: nets,
    });
  }
}
