import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { CardComponent } from '../../../ui/card/card.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { StepsComponent, StepItem } from '../../../ui/steps/steps.component';
import { KeyCreatedDialogComponent } from '../key-created/key-created-dialog.component';
import { IssuedKey } from '../keys/keys.model';
import { KeyWizardApi } from './key-wizard.api';
import { IntegrationCatalogView, NewKeyBody, validateWizardStep1 } from './key-wizard.model';
import { ServicesTreeComponent } from './services-tree.component';
import { StepOwnerComponent } from './step-owner.component';

@Component({
  selector: 'app-key-wizard-page',
  standalone: true,
  imports: [
    ReactiveFormsModule, TPipe, ButtonComponent, IconComponent, CardComponent,
    ToggleComponent, StepsComponent, ServicesTreeComponent, StepOwnerComponent, KeyCreatedDialogComponent,
  ],
  templateUrl: './key-wizard.page.html',
  styleUrl: './key-wizard.page.css',
})
export class KeyWizardPage implements OnInit {
  private readonly api = inject(KeyWizardApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly i18n = inject(I18n);
  readonly sessionStore = inject(SessionStore);

  readonly currentStep = signal<number>(0);
  readonly editKeyId = signal<string | null>(null);
  readonly catalog = signal<IntegrationCatalogView[]>([]);
  readonly loading = signal<boolean>(true);
  readonly submitting = signal<boolean>(false);
  readonly serverError = signal<string | null>(null);
  readonly createdKey = signal<IssuedKey | null>(null);

  readonly selectedScopes = signal<string[]>([]);
  readonly deniedTools = signal<string[]>([]);
  readonly fieldErrors = signal<Record<string, string>>({});

  readonly form = new FormGroup({
    owner: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    kind: new FormControl<'person' | 'server' | 'demo'>('person', { nonNullable: true }),
    days: new FormControl(30, { nonNullable: true, validators: [Validators.required, Validators.min(1)] }),
    networks: new FormControl('', { nonNullable: true }),
    ruOnly: new FormControl(true, { nonNullable: true }),
    geoReason: new FormControl('', { nonNullable: true }),
    rateLimit: new FormControl(60, { nonNullable: true, validators: [Validators.min(1)] }),
    readOnly: new FormControl(false, { nonNullable: true }),
  });

  readonly stepItems: StepItem[] = [
    { label: this.i18n.t('mcp.wizard.step1') },
    { label: this.i18n.t('mcp.wizard.step2') },
    { label: this.i18n.t('mcp.wizard.step3') },
  ];

  readonly maxDays = computed(() => {
    const k = this.form.controls.kind.value;
    const hasNets = this.parsedNetworks().length > 0;
    if (k === 'demo') return 14;
    if (k === 'server') return 365;
    return hasNets ? 90 : 30;
  });

  readonly expiryDate = computed(() => {
    const days = this.form.controls.days.value || 0;
    return new Date(Date.now() + days * 86400000).toLocaleDateString('ru-RU');
  });

  readonly selectedToolsCount = computed(() => {
    const scopes = this.selectedScopes();
    let total = 0;
    for (const item of this.catalog()) {
      for (const t of item.tools) {
        if (scopes.includes(t.scope) && !this.deniedTools().includes(t.name)) total++;
      }
    }
    return total;
  });

  ngOnInit(): void {
    if (!this.sessionStore.canChange()) {
      this.router.navigate(['/mcp/keys']);
      return;
    }
    this.form.controls.kind.valueChanges.subscribe((k) => {
      if (k === 'demo' && this.form.controls.days.value > 14) this.form.controls.days.setValue(7);
      else if (k === 'server' && this.form.controls.days.value === 30) this.form.controls.days.setValue(90);
    });
    const editId = this.route.snapshot.queryParamMap.get('edit');
    if (editId) this.editKeyId.set(editId);
    this.api.catalog().subscribe({
      next: (data) => {
        this.catalog.set(data);
        this.loading.set(false);
        if (editId) this.loadExistingKey(editId);
      },
      error: () => this.loading.set(false),
    });
  }

  private loadExistingKey(id: string): void {
    this.api.getKey(id).subscribe((k) => {
      if (!k) return;
      this.form.patchValue({
        owner: k.owner,
        kind: (k.kind as 'person' | 'server' | 'demo') || 'person',
        networks: k.networks ? k.networks.join(', ') : '',
        ruOnly: k.geo === 'ru_only',
        geoReason: k.geoReason || '',
        readOnly: k.readOnly,
      });
      this.selectedScopes.set([...k.scopes]);
      this.deniedTools.set([...k.deniedTools]);
    });
  }

  parsedNetworks(): string[] {
    return this.form.controls.networks.value.split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
  }

  nextStep(): void {
    this.serverError.set(null);
    if (this.currentStep() === 0) {
      const errs = validateWizardStep1(this.form.getRawValue(), this.maxDays(), this.i18n);
      this.fieldErrors.set(errs);
      if (Object.keys(errs).length > 0) return;
    }
    if (this.currentStep() === 1) {
      if (this.selectedScopes().length === 0) {
        this.fieldErrors.set({ scopes: this.i18n.t('mcp.wizard.errorNoRights') });
        return;
      }
      this.fieldErrors.set({});
    }
    this.currentStep.set(Math.min(2, this.currentStep() + 1));
  }

  prevStep(): void {
    this.serverError.set(null);
    this.currentStep.set(Math.max(0, this.currentStep() - 1));
  }

  cancel(): void {
    this.router.navigate(['/mcp/keys']);
  }

  submit(): void {
    this.submitting.set(true);
    this.serverError.set(null);
    const v = this.form.getRawValue();
    const editId = this.editKeyId();
    const nets = this.parsedNetworks();
    const geo = v.ruOnly ? 'ru_only' : 'any';
    const geoReason = v.ruOnly ? undefined : v.geoReason;

    if (editId) {
      this.api.updatePermissions(editId, {
        scopes: this.selectedScopes(), deniedTools: this.deniedTools(),
        readOnly: v.readOnly, networks: nets, geo, geoReason,
      }).subscribe({
        next: () => { this.submitting.set(false); this.router.navigate(['/mcp/keys']); },
        error: (err: { code?: string; message?: string }) => {
          this.submitting.set(false);
          this.serverError.set(err.code || err.message || 'Ошибка обновления прав');
        },
      });
    } else {
      const body: NewKeyBody = {
        owner: v.owner.trim(), kind: v.kind, days: v.days, networks: nets,
        scopes: this.selectedScopes(), deniedTools: this.deniedTools(),
        write: !v.readOnly, geo, geoReason,
      };
      this.api.create(body).subscribe({
        next: (issued) => { this.submitting.set(false); this.createdKey.set(issued); },
        error: (err: { code?: string; message?: string }) => {
          this.submitting.set(false);
          this.serverError.set(err.code || err.message || 'Ошибка выдачи ключа');
        },
      });
    }
  }

  onKeySaved(): void {
    this.createdKey.set(null);
    this.router.navigate(['/mcp/keys']);
  }
}
