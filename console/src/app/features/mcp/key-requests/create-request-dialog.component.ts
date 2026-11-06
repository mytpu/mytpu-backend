import { Component, computed, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { merge } from 'rxjs';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SelectComponent } from '../../../ui/select/select.component';
import { I18n } from '../../../core/i18n/i18n.service';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { validateCidr } from '../key-wizard/key-wizard.model';
import { CreateKeyRequestBody } from './key-requests.model';

@Component({
  selector: 'app-create-request-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, TPipe, SelectComponent, ButtonComponent, DialogComponent, IconComponent],
  templateUrl: './create-request-dialog.component.html',
  styleUrl: './create-request-dialog.component.css',
})
export class CreateRequestDialogComponent {
  private readonly i18n = inject(I18n);

  readonly submitting = input<boolean>(false);
  readonly serverError = input<string | null>(null);
  readonly availableScopes = input<string[]>([]);

  readonly submitRequest = output<CreateKeyRequestBody>();
  readonly cancel = output<void>();

  readonly submitted = signal<boolean>(false);
  readonly selectedScopes = signal<string[]>(['docs.read']);

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

  private readonly formEvents = toSignal(
    merge(this.form.statusChanges, this.form.valueChanges),
  );

  readonly fieldErrors = computed(() => {
    this.formEvents();
    const isSubmitted = this.submitted();
    const errs: Record<string, string | null> = {};

    const req = this.form.controls.requester;
    if ((isSubmitted || req.touched) && req.errors?.['required']) {
      errs['requester'] = this.i18n.t('mcp.requests.errorRequesterRequired');
    }

    const c = this.form.controls.contact;
    if ((isSubmitted || c.touched) && c.errors) {
      if (c.errors['required'] || c.errors['email']) {
        errs['contact'] = this.i18n.t('mcp.requests.errorEmailInvalid');
      }
    }

    const p = this.form.controls.purpose;
    if ((isSubmitted || p.touched) && p.errors?.['required']) {
      errs['purpose'] = this.i18n.t('mcp.requests.errorPurposeRequired');
    }

    const net = this.form.controls.networks;
    if ((isSubmitted || net.touched) && net.errors) {
      if (net.errors['required']) {
        errs['networks'] = this.i18n.t('mcp.requests.errorNetworksRequired');
      } else if (net.errors['invalid']) {
        errs['networks'] = this.i18n.t('mcp.requests.errorNetworkInvalid');
      }
    }

    return errs;
  });

  toggleScope(scope: string): void {
    const cur = this.selectedScopes();
    if (cur.includes(scope)) {
      this.selectedScopes.set(cur.filter((s) => s !== scope));
    } else {
      this.selectedScopes.set([...cur, scope]);
    }
  }

  onSubmit(): void {
    this.submitted.set(true);
    const val = this.form.getRawValue();
    const nets = val.networks.split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    if (val.kind === 'server' && nets.length === 0) {
      this.form.controls.networks.setErrors({ required: true });
      return;
    }
    for (const net of nets) {
      const chk = validateCidr(net);
      if (!chk.valid) {
        this.form.controls.networks.setErrors({ invalid: true });
        return;
      }
    }
    if (this.form.invalid) {
      return;
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
