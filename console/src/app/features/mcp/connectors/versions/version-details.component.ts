import { Component, inject, input } from '@angular/core';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { ConnectorVersion } from '../connectors.model';

@Component({
  selector: 'app-version-details',
  standalone: true,
  imports: [TPipe],
  templateUrl: './version-details.component.html',
  styleUrl: './version-details.component.css',
})
export class VersionDetailsComponent {
  private readonly i18n = inject(I18n);

  readonly version = input.required<ConnectorVersion>();

  getAuthLabel(type: string | undefined): string {
    switch (type) {
      case 'none':
        return this.i18n.t('connectors.specAuthNone');
      case 'header':
        return this.i18n.t('connectors.specAuthHeader');
      case 'query':
        return this.i18n.t('connectors.specAuthQuery');
      case 'basic':
        return this.i18n.t('connectors.specAuthBasic');
      case 'oauth2':
        return this.i18n.t('connectors.specAuthOauth2');
      default:
        return type || '—';
    }
  }
}
