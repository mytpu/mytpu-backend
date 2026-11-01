import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { ApproveBody, KeyRequestView } from './key-requests.model';

@Component({
  selector: 'app-key-requests-card',
  standalone: true,
  imports: [FormsModule, TPipe, BadgeComponent, ButtonComponent, CardComponent, IconComponent, ToggleComponent],
  templateUrl: './key-requests-card.component.html',
  styleUrl: './key-requests-card.component.css',
})
export class KeyRequestsCardComponent {
  private readonly i18n = inject(I18n);

  readonly request = input.required<KeyRequestView>();
  readonly canChange = input.required<boolean>();
  readonly submitting = input<boolean>(false);
  readonly availableScopes = input<string[]>([]);
  readonly serverError = input<string | null>(null);

  readonly approve = output<ApproveBody>();
  readonly reject = output<void>();

  readonly decisionKind = signal<'person' | 'server' | 'demo'>('server');
  readonly decisionDays = signal<number>(90);
  readonly decisionNetworks = signal<string>('10.2.0.0/24');
  readonly decisionScopes = signal<string[]>(['docs.read']);
  readonly decisionRuOnly = signal<boolean>(true);
  readonly decisionGeoReason = signal<string>('');
  readonly showScopePicker = signal<boolean>(false);
  readonly geoError = signal<string | null>(null);

  readonly statusText = computed(() => {
    const s = this.request().status;
    if (s === 'approved') return this.i18n.t('mcp.requests.statusApproved');
    if (s === 'rejected') return this.i18n.t('mcp.requests.statusRejected');
    return this.i18n.t('mcp.requests.statusNew');
  });

  constructor() {
    effect(() => {
      const req = this.request();
      if (req) {
        this.initFromRequest(req);
      }
    });
  }

  readonly stepNow = computed(() => {
    const st = this.request().status;
    if (st === 'approved') return 2;
    return 1;
  });

  readonly maxDays = computed(() => {
    const k = this.decisionKind();
    const hasNets = this.decisionNetworks().trim().length > 0;
    if (k === 'demo') return 14;
    if (k === 'server') return 365;
    return hasNets ? 90 : 30;
  });

  initFromRequest(req: KeyRequestView): void {
    const k = (req.kind as 'person' | 'server' | 'demo') || 'person';
    this.decisionKind.set(k);
    this.decisionDays.set(req.days || (k === 'server' ? 90 : k === 'demo' ? 7 : 30));
    this.decisionNetworks.set(req.networks ? req.networks.join(', ') : '');
    this.decisionScopes.set(req.scopes?.length ? [...req.scopes] : ['docs.read']);
    this.decisionRuOnly.set(true);
    this.decisionGeoReason.set('');
    this.geoError.set(null);
  }

  setKind(kind: 'person' | 'server' | 'demo'): void {
    this.decisionKind.set(kind);
    if (kind === 'demo' && this.decisionDays() > 14) this.decisionDays.set(7);
    if (kind === 'server' && this.decisionDays() < 30) this.decisionDays.set(90);
  }

  toggleScope(scope: string): void {
    const cur = this.decisionScopes();
    if (cur.includes(scope)) {
      this.decisionScopes.set(cur.filter((s) => s !== scope));
    } else {
      this.decisionScopes.set([...cur, scope]);
    }
  }

  onToggleRuOnly(val: boolean): void {
    this.decisionRuOnly.set(val);
    if (val) this.geoError.set(null);
  }

  onApprove(): void {
    if (!this.decisionRuOnly() && !this.decisionGeoReason().trim()) {
      this.geoError.set(this.i18n.t('mcp.requests.geoReasonPlaceholder'));
      return;
    }
    const nets = this.decisionNetworks().split(/[,\n]/).map((s) => s.trim()).filter(Boolean);
    const body: ApproveBody = {
      owner: this.request().requester,
      kind: this.decisionKind(),
      days: this.decisionDays(),
      networks: nets,
      scopes: this.decisionScopes(),
      geo: this.decisionRuOnly() ? 'ru_only' : 'any',
      geoReason: this.decisionRuOnly() ? undefined : this.decisionGeoReason().trim(),
    };
    this.approve.emit(body);
  }
}
