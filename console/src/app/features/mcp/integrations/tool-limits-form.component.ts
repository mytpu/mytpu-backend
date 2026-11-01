import { Component, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IntegrationView, LimitsChange, ToolView } from './integrations.model';

@Component({
  selector: 'app-tool-limits-form',
  standalone: true,
  imports: [FormsModule, TPipe, BadgeComponent, ButtonComponent],
  template: `
    <div class="limits-body">
      <p class="tool-desc">{{ tool().description }}</p>

      <div class="field-item">
        <label class="field-label">{{ 'mcp.integrations.cacheTtlLabel' | t }}</label>
        <input
          type="number"
          class="field-input"
          [disabled]="!canChange() || saving()"
          [ngModel]="cacheTtl()"
          (ngModelChange)="cacheTtl.set($event)"
          min="0"
          max="86400"
        />
        <span class="field-hint">{{ 'mcp.integrations.cacheTtlHint' | t: { sec: defaultCacheTtl() } }}</span>
      </div>

      <div class="field-item">
        <label class="field-label">{{ 'mcp.integrations.timeoutLabel' | t }}</label>
        <input
          type="number"
          class="field-input"
          [disabled]="!canChange() || saving()"
          [ngModel]="timeoutMs()"
          (ngModelChange)="timeoutMs.set($event)"
          min="100"
          max="60000"
        />
        <span class="field-hint">{{ 'mcp.integrations.timeoutHint' | t: { ms: defaultTimeoutMs() } }}</span>
      </div>

      <div class="field-item">
        <label class="field-label">{{ 'mcp.integrations.rateLimitLabel' | t }}</label>
        <input
          type="number"
          class="field-input"
          [disabled]="!canChange() || saving()"
          [ngModel]="ratePerMinute()"
          (ngModelChange)="ratePerMinute.set($event)"
          min="1"
          max="10000"
        />
        <span class="field-hint">{{ 'mcp.integrations.rateLimitHint' | t: { rpm: defaultRatePerMinute() } }}</span>
      </div>

      <div class="secrets-box">
        <span class="secrets-title">{{ 'mcp.integrations.secretsTitle' | t }}</span>
        <div class="secrets-chips">
          @if (integration()?.missingSecrets?.length) {
            @for (sec of integration()!.missingSecrets; track sec) {
              <app-badge kind="warn" [dot]="true">
                {{ 'mcp.integrations.secretMissing' | t: { name: sec } }}
              </app-badge>
            }
          } @else {
            <app-badge kind="ok" [dot]="true">
              {{ 'mcp.integrations.secretConfigured' | t: { name: integration()?.id + '_api_token' } }}
            </app-badge>
          }
        </div>
      </div>

      @if (!canChange()) {
        <span class="readonly-note">{{ 'actions.readonly' | t }}</span>
      }

      <div class="actions-row">
        <app-button
          kind="ghost"
          [disabled]="!canChange() || saving() || !hasCustomLimits()"
          (btnClick)="onReset()"
        >
          {{ 'mcp.integrations.btnReset' | t }}
        </app-button>
        <div class="spacer"></div>
        <app-button kind="primary" [disabled]="!canChange() || saving()" (btnClick)="onSave()">
          {{ 'mcp.integrations.btnSave' | t }}
        </app-button>
      </div>
    </div>
  `,
  styles: `
    :host { display: block; width: 100%; }
    .limits-body { display: flex; flex-direction: column; gap: var(--space-3); }
    .tool-desc { margin: 0; font-size: 13px; line-height: 1.4; color: var(--text-secondary); }
    .field-item { display: flex; flex-direction: column; gap: 4px; }
    .field-label { font-size: 13px; font-weight: 600; color: var(--text-primary); }
    .field-input {
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      background: var(--bg-nested);
      color: var(--text-primary);
      padding: 8px 12px;
      font-size: 14px;
      outline: none;
    }
    .field-input:focus { border-color: var(--green-action); }
    .field-input:disabled { opacity: 0.6; cursor: not-allowed; }
    .field-hint { font-size: 12px; color: var(--text-muted); }
    .secrets-box { margin-top: 4px; display: flex; flex-direction: column; gap: 6px; }
    .secrets-title {
      font-size: 12px;
      font-weight: 700;
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .secrets-chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .readonly-note {
      font-size: 12px;
      color: var(--warn-text);
      background: var(--warn-soft);
      padding: 6px 10px;
      border-radius: var(--radius-button);
    }
    .actions-row {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      margin-top: var(--space-2);
    }
    .spacer { flex: 1; }
  `,
})
export class ToolLimitsFormComponent {
  readonly tool = input.required<ToolView>();
  readonly integration = input<IntegrationView['integration'] | null>(null);
  readonly canChange = input<boolean>(true);
  readonly saving = input<boolean>(false);

  readonly save = output<LimitsChange>();
  readonly reset = output<void>();

  readonly cacheTtl = signal<number>(0);
  readonly timeoutMs = signal<number>(0);
  readonly ratePerMinute = signal<number>(0);

  readonly defaultCacheTtl = signal<number>(300);
  readonly defaultTimeoutMs = signal<number>(3000);
  readonly defaultRatePerMinute = signal<number>(60);

  constructor() {
    effect(() => {
      const t = this.tool();
      this.cacheTtl.set(t.cacheTtlSeconds);
      this.timeoutMs.set(t.timeoutMs);
      this.ratePerMinute.set(t.ratePerMinute);
      this.defaultCacheTtl.set(t.override ? 300 : t.cacheTtlSeconds);
      this.defaultTimeoutMs.set(t.override ? 3000 : t.timeoutMs);
      this.defaultRatePerMinute.set(t.override ? 60 : t.ratePerMinute);
    });
  }

  hasCustomLimits(): boolean {
    return !!this.tool().override;
  }

  onReset(): void {
    this.reset.emit();
  }

  onSave(): void {
    this.save.emit({
      cacheTtlSeconds: this.cacheTtl(),
      timeoutMs: this.timeoutMs(),
      ratePerMinute: this.ratePerMinute(),
    });
  }
}
