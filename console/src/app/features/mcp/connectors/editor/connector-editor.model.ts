import { AbstractControl, FormArray, FormControl, FormGroup, ValidationErrors, Validators } from '@angular/forms';
import {
  ConnectorAuth, ConnectorAuthType, ConnectorField,
  ConnectorParam, ConnectorParamIn, ConnectorParamType,
  ConnectorResultMode, ConnectorSpec, ConnectorToolSpec,
} from '../connectors.model';

export interface FieldForm {
  name: FormControl<string>;
  pointer: FormControl<string>;
  label: FormControl<string>;
}

export interface ParamForm {
  name: FormControl<string>;
  in: FormControl<ConnectorParamIn>;
  type: FormControl<ConnectorParamType>;
  required: FormControl<boolean>;
  default: FormControl<string>;
  description: FormControl<string>;
}

export interface ResultForm {
  pointer: FormControl<string>;
  mode: FormControl<ConnectorResultMode>;
  maxItems: FormControl<number | null>;
  fields: FormArray<FormGroup<FieldForm>>;
}

export interface ToolForm {
  name: FormControl<string>;
  description: FormControl<string>;
  path: FormControl<string>;
  summary: FormControl<string>;
  cacheTtlSeconds: FormControl<number | null>;
  timeoutMs: FormControl<number | null>;
  ratePerMinute: FormControl<number | null>;
  result: FormGroup<ResultForm>;
  params: FormArray<FormGroup<ParamForm>>;
}

export interface AuthForm {
  type: FormControl<ConnectorAuthType>;
  name: FormControl<string>;
  tokenUrl: FormControl<string>;
  oauthScope: FormControl<string>;
}

export interface ScopeForm {
  name: FormControl<string>;
  title: FormControl<string>;
  description: FormControl<string>;
}

export interface ConnectorForm {
  id: FormControl<string>;
  title: FormControl<string>;
  baseUrl: FormControl<string>;
  auth: FormGroup<AuthForm>;
  scope: FormGroup<ScopeForm>;
  tools: FormArray<FormGroup<ToolForm>>;
}

/** Шаблоны имён — как в шлюзе (`ConnectorValidator`): консоль не должна быть строже или мягче сервера. */
const NAME_RULES = {
  connector: /^[a-z][a-z0-9_]{1,31}$/,
  tool: /^[a-z][a-z0-9_]{1,63}$/,
  param: /^[a-z][a-zA-Z0-9_]{0,31}$/,
  field: /^[a-zA-Z][a-zA-Z0-9_]{0,31}$/,
  scope: /^[a-z][a-z0-9_]{1,31}\.read$/,
} as const;

export function nameValidator(kind: keyof typeof NAME_RULES): (c: AbstractControl) => ValidationErrors | null {
  return (c) => (c.value && !NAME_RULES[kind].test(c.value) ? { identifier: kind } : null);
}

export function identifierValidator(c: AbstractControl): ValidationErrors | null {
  return nameValidator('connector')(c);
}

export function httpsUrlValidator(c: AbstractControl): ValidationErrors | null {
  const v = (c.value || '').trim();
  return v && !v.startsWith('https://') ? { onlyHttps: true } : null;
}

export function privateIpValidator(c: AbstractControl): ValidationErrors | null {
  const v = (c.value || '').trim();
  if (!v) return null;
  try {
    const host = new URL(v).hostname.toLowerCase();
    if (isPrivateHost(host)) return { privateIp: true };
  } catch {
    const raw = v.replace(/^https?:\/\//i, '').split('/')[0].split(':')[0].toLowerCase();
    if (isPrivateHost(raw)) return { privateIp: true };
  }
  return null;
}

function isPrivateHost(h: string): boolean {
  if (h === 'localhost' || h.startsWith('127.') || h.startsWith('10.') || h.startsWith('169.254.') || h.startsWith('192.168.')) return true;
  const m = h.match(/^172\.(\d+)\./);
  return !!(m && parseInt(m[1], 10) >= 16 && parseInt(m[1], 10) <= 31);
}

export function updateToolPathValidation(toolGroup: FormGroup<ToolForm>): void {
  const pathCtrl = toolGroup.controls.path;
  const paramsArray = toolGroup.controls.params;
  if (!pathCtrl || !paramsArray) return;

  const pathMatches = Array.from((pathCtrl.value || '').matchAll(/\{([a-zA-Z0-9_]+)\}/g)).map((m) => m[1]);
  const pathSet = new Set(pathMatches);
  const declaredPath = new Set<string>();

  for (let i = 0; i < paramsArray.length; i++) {
    const p = paramsArray.at(i);
    if (p.controls.in.value === 'path' && p.controls.name.value.trim()) {
      declaredPath.add(p.controls.name.value.trim());
    }
  }

  const mismatch = pathSet.size !== declaredPath.size || [...pathSet].some((p) => !declaredPath.has(p));
  const errs = pathCtrl.errors ? { ...pathCtrl.errors } : null;
  if (mismatch) {
    pathCtrl.setErrors({ ...errs, pathParamsMismatch: true });
  } else if (errs && errs['pathParamsMismatch']) {
    delete errs['pathParamsMismatch'];
    pathCtrl.setErrors(Object.keys(errs).length > 0 ? errs : null);
  }
}

export function createFieldGroup(initial?: ConnectorField): FormGroup<FieldForm> {
  return new FormGroup<FieldForm>({
    name: new FormControl(initial?.name || '', { nonNullable: true, validators: [Validators.required, nameValidator('field')] }),
    pointer: new FormControl(initial?.pointer ?? '', { nonNullable: true, validators: [Validators.required] }),
    label: new FormControl(initial?.label || '', { nonNullable: true, validators: [Validators.required] }),
  });
}

export function createParamGroup(initial?: ConnectorParam): FormGroup<ParamForm> {
  return new FormGroup<ParamForm>({
    name: new FormControl(initial?.name || '', { nonNullable: true, validators: [Validators.required, nameValidator('param')] }),
    in: new FormControl<ConnectorParamIn>(initial?.in || 'query', { nonNullable: true, validators: [Validators.required] }),
    type: new FormControl<ConnectorParamType>(initial?.type || 'string', { nonNullable: true, validators: [Validators.required] }),
    required: new FormControl(initial?.required ?? false, { nonNullable: true }),
    default: new FormControl(initial?.default !== undefined && initial?.default !== null ? String(initial.default) : '', { nonNullable: true }),
    description: new FormControl(initial?.description || '', { nonNullable: true }),
  });
}

export function createToolGroup(initial?: ConnectorToolSpec): FormGroup<ToolForm> {
  const fields = (initial?.result?.fields || []).map((f) => createFieldGroup(f));
  const params = (initial?.params || []).map((p) => createParamGroup(p));
  const g = new FormGroup<ToolForm>({
    name: new FormControl(initial?.name || '', { nonNullable: true, validators: [Validators.required, nameValidator('tool')] }),
    description: new FormControl(initial?.description || '', { nonNullable: true, validators: [Validators.required] }),
    path: new FormControl(initial?.path || '', { nonNullable: true, validators: [Validators.required] }),
    summary: new FormControl(initial?.summary || '', { nonNullable: true, validators: [Validators.required] }),
    cacheTtlSeconds: new FormControl<number | null>(initial?.cacheTtlSeconds ?? null),
    timeoutMs: new FormControl<number | null>(initial?.timeoutMs ?? null),
    ratePerMinute: new FormControl<number | null>(initial?.ratePerMinute ?? null),
    result: new FormGroup<ResultForm>({
      pointer: new FormControl(initial?.result?.pointer ?? '', { nonNullable: true }),
      mode: new FormControl<ConnectorResultMode>(initial?.result?.mode || 'object', { nonNullable: true, validators: [Validators.required] }),
      maxItems: new FormControl<number | null>(initial?.result?.maxItems ?? null),
      fields: new FormArray<FormGroup<FieldForm>>(fields),
    }),
    params: new FormArray<FormGroup<ParamForm>>(params),
  });
  updateToolPathValidation(g);
  return g;
}

export function buildConnectorSpec(form: FormGroup<ConnectorForm>): ConnectorSpec {
  const r = form.getRawValue();
  const auth: ConnectorAuth = {
    type: r.auth.type,
    name: r.auth.type === 'header' || r.auth.type === 'query' ? r.auth.name.trim() : null,
    tokenUrl: r.auth.type === 'oauth2' ? r.auth.tokenUrl.trim() : null,
    oauthScope: r.auth.type === 'oauth2' ? r.auth.oauthScope.trim() : null,
  };
  const tools: ConnectorToolSpec[] = r.tools.map((t) => ({
    name: t.name.trim(),
    description: t.description.trim(),
    path: t.path.trim(),
    params: t.params.map((p) => {
      let def: unknown = undefined;
      const s = p.default.trim();
      if (s) def = p.type === 'integer' || p.type === 'number' ? Number(s) : p.type === 'boolean' ? s === 'true' : s;
      return { name: p.name.trim(), in: p.in, type: p.type, required: p.required, default: def, description: p.description.trim() || undefined };
    }),
    result: {
      pointer: t.result.pointer.trim(),
      mode: t.result.mode,
      fields: t.result.fields.map((f) => ({ name: f.name.trim(), pointer: f.pointer.trim(), label: f.label.trim() })),
      maxItems: t.result.maxItems ? Number(t.result.maxItems) : undefined,
    },
    summary: t.summary.trim(),
    cacheTtlSeconds: t.cacheTtlSeconds ? Number(t.cacheTtlSeconds) : undefined,
    timeoutMs: t.timeoutMs ? Number(t.timeoutMs) : undefined,
    ratePerMinute: t.ratePerMinute ? Number(t.ratePerMinute) : undefined,
  }));
  return {
    title: r.title.trim(),
    baseUrl: r.baseUrl.trim(),
    auth,
    scope: { name: r.scope.name.trim(), title: r.scope.title.trim(), description: r.scope.description.trim() || null },
    tools,
  };
}
