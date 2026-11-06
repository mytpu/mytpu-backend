import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { I18n } from '../../../../core/i18n/i18n.service';
import { SessionStore } from '../../../../core/session/session.store';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../../ui/skeleton/skeleton.component';
import { ConnectorsApi } from '../connectors.api';
import { ConnectorAuthType, ConnectorSpec } from '../connectors.model';
import {
  AuthForm, buildConnectorSpec, ConnectorForm, createToolGroup,
  httpsUrlValidator, identifierValidator, nameValidator, privateIpValidator, ScopeForm, ToolForm,
} from './connector-editor.model';
import { EditorToolComponent } from './editor-tool.component';
import { EditorAuthComponent } from './editor-auth.component';
import { EditorScopeComponent } from './editor-scope.component';

@Component({
  selector: 'app-connector-editor-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TPipe,
    ButtonComponent,
    IconComponent,
    SkeletonComponent,
    EditorToolComponent,
    EditorAuthComponent,
    EditorScopeComponent,
  ],
  templateUrl: './connector-editor.page.html',
  styleUrl: './connector-editor.page.css',
})
export class ConnectorEditorPage implements OnInit {
  private readonly api = inject(ConnectorsApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly i18n = inject(I18n);
  readonly sessionStore = inject(SessionStore);

  readonly isNew = signal<boolean>(true);
  readonly connectorId = signal<string>('');
  readonly loading = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly submitted = signal<boolean>(false);
  readonly formValid = signal<boolean>(false);
  readonly serverProblems = signal<string[]>([]);
  readonly serverError = signal<string | null>(null);

  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly form = new FormGroup<ConnectorForm>({
    id: new FormControl('', { nonNullable: true, validators: [Validators.required, identifierValidator] }),
    title: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    baseUrl: new FormControl('', { nonNullable: true, validators: [Validators.required, httpsUrlValidator, privateIpValidator] }),
    auth: new FormGroup<AuthForm>({
      type: new FormControl<ConnectorAuthType>('none', { nonNullable: true, validators: [Validators.required] }),
      name: new FormControl('', { nonNullable: true }),
      tokenUrl: new FormControl('', { nonNullable: true }),
      oauthScope: new FormControl('', { nonNullable: true }),
    }),
    scope: new FormGroup<ScopeForm>({
      name: new FormControl('', { nonNullable: true, validators: [Validators.required, nameValidator('scope')] }),
      title: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
      description: new FormControl('', { nonNullable: true }),
    }),
    tools: new FormArray<FormGroup<ToolForm>>([]),
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.form.statusChanges.subscribe(() => this.recalcValidity());
    this.form.valueChanges.subscribe(() => this.recalcValidity());

    if (id) {
      this.isNew.set(false);
      this.connectorId.set(id);
      this.form.controls.id.setValue(id);
      this.form.controls.id.disable();
      this.loadExisting(id);
    } else {
      this.isNew.set(true);
      this.addTool();
      this.recalcValidity();
    }
  }

  private loadExisting(id: string): void {
    this.loading.set(true);
    this.api.details(id).subscribe({
      next: (details) => {
        this.loading.set(false);
        const latest = details.versions[0]?.spec;
        if (latest) {
          this.populateForm(id, latest);
        } else {
          this.addTool();
        }
        this.recalcValidity();
      },
      error: (err) => {
        this.loading.set(false);
        this.serverError.set(err.message || this.i18n.t('connectors.errorDetails'));
      },
    });
  }

  private populateForm(id: string, spec: ConnectorSpec): void {
    this.form.controls.id.setValue(id);
    this.form.controls.title.setValue(spec.title);
    this.form.controls.baseUrl.setValue(spec.baseUrl);
    this.form.controls.auth.setValue({
      type: spec.auth?.type || 'none',
      name: spec.auth?.name || '',
      tokenUrl: spec.auth?.tokenUrl || '',
      oauthScope: spec.auth?.oauthScope || '',
    });
    this.form.controls.scope.setValue({
      name: spec.scope?.name || '',
      title: spec.scope?.title || '',
      description: spec.scope?.description || '',
    });
    this.form.controls.tools.clear();
    for (const t of spec.tools || []) {
      this.form.controls.tools.push(createToolGroup(t));
    }
    this.recalcValidity();
  }

  addTool(): void {
    this.form.controls.tools.push(createToolGroup());
    this.recalcValidity();
  }

  removeTool(idx: number): void {
    this.form.controls.tools.removeAt(idx);
    this.recalcValidity();
  }

  recalcValidity(): void {
    const hasTools = this.form.controls.tools.length > 0;
    this.formValid.set(this.form.valid && hasTools);
  }

  goBack(): void {
    const id = this.connectorId();
    if (!this.isNew() && id) {
      this.router.navigate(['/mcp/connectors', id]);
    } else {
      this.router.navigate(['/mcp/connectors']);
    }
  }

  onSubmit(): void {
    this.submitted.set(true);
    this.form.markAllAsTouched();
    this.recalcValidity();

    if (!this.formValid() || !this.canChange() || this.saving()) {
      return;
    }

    const id = this.isNew() ? this.form.controls.id.value.trim() : this.connectorId();
    const spec = buildConnectorSpec(this.form);

    this.saving.set(true);
    this.serverProblems.set([]);
    this.serverError.set(null);

    this.api.saveVersion(id, spec).subscribe({
      next: (res) => {
        this.saving.set(false);
        this.router.navigate(['/mcp/connectors', res.id], {
          state: { savedVersion: res.version },
          queryParams: { saved: res.version },
        });
      },
      error: (err) => {
        this.saving.set(false);
        const raw = err.raw as { error?: string; message?: string; problems?: string[] } | undefined;
        if (raw?.problems && raw.problems.length > 0) {
          this.serverProblems.set(raw.problems);
        } else {
          this.serverError.set(this.i18n.t('connectors.editor.errorSave'));
        }
      },
    });
  }
}
