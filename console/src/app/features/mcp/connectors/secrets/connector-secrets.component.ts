import { Component, computed, inject, input, output, signal } from '@angular/core';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { DialogComponent } from '../../../../ui/dialog/dialog.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import { ConnectorsApi } from '../connectors.api';
import { ConnectorVersion } from '../connectors.model';

@Component({
  selector: 'app-connector-secrets',
  standalone: true,
  imports: [TPipe, BadgeComponent, ButtonComponent, DialogComponent, IconComponent],
  templateUrl: './connector-secrets.component.html',
  styleUrl: './connector-secrets.component.css',
})
export class ConnectorSecretsComponent {
  private readonly api = inject(ConnectorsApi);
  private readonly i18n = inject(I18n);

  readonly connectorId = input.required<string>();
  readonly latestVersion = input.required<ConnectorVersion>();
  readonly secretsSet = input.required<string[]>();
  readonly canChange = input<boolean>(true);

  readonly secretsChange = output<void>();

  readonly savingSecret = signal<string | null>(null);
  readonly deletingSecret = signal<string | null>(null);
  readonly secretToDelete = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly neededSecrets = computed<string[]>(() => {
    const authType = this.latestVersion()?.spec?.auth?.type;
    switch (authType) {
      case 'header':
      case 'query':
        return ['api_key'];
      case 'basic':
        return ['username', 'password'];
      case 'oauth2':
        return ['client_id', 'client_secret'];
      default:
        return [];
    }
  });

  getSecretLabel(name: string): string {
    const key = `connectors.secretLabel_${name}`;
    const translated = this.i18n.t(key);
    return translated !== key ? translated : name;
  }

  isSecretSet(name: string): boolean {
    return this.secretsSet().includes(name);
  }

  saveSecret(name: string, inputEl: HTMLInputElement): void {
    const val = inputEl.value;
    if (!val || !this.canChange() || this.savingSecret() === name) return;

    this.savingSecret.set(name);
    this.error.set(null);

    this.api.setSecret(this.connectorId(), name, val).subscribe({
      next: () => {
        inputEl.value = '';
        this.savingSecret.set(null);
        this.secretsChange.emit();
      },
      error: (err) => {
        this.savingSecret.set(null);
        this.error.set(err.message || this.i18n.t('connectors.error'));
      },
    });
  }

  promptDelete(name: string): void {
    if (!this.canChange()) return;
    this.secretToDelete.set(name);
  }

  cancelDelete(): void {
    this.secretToDelete.set(null);
  }

  confirmDelete(): void {
    const name = this.secretToDelete();
    if (!name || !this.canChange() || this.deletingSecret() === name) return;

    this.deletingSecret.set(name);
    this.error.set(null);

    this.api.deleteSecret(this.connectorId(), name).subscribe({
      next: () => {
        this.deletingSecret.set(null);
        this.secretToDelete.set(null);
        this.secretsChange.emit();
      },
      error: (err) => {
        this.deletingSecret.set(null);
        this.error.set(err.message || this.i18n.t('connectors.error'));
      },
    });
  }
}
