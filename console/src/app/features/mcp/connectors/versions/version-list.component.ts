import { Component, input, output } from '@angular/core';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { ConnectorVersion } from '../connectors.model';

@Component({
  selector: 'app-version-list',
  standalone: true,
  imports: [TPipe, BadgeComponent, ButtonComponent],
  templateUrl: './version-list.component.html',
  styleUrl: './version-list.component.css',
})
export class VersionListComponent {
  readonly versions = input.required<ConnectorVersion[]>();
  readonly selectedVersion = input<ConnectorVersion | null>(null);
  readonly publishedVersion = input<number | null | undefined>(null);
  readonly canChange = input<boolean>(true);

  readonly selectVersion = output<ConnectorVersion>();
  readonly publish = output<ConnectorVersion>();
  readonly unpublish = output<void>();

  formatDate(iso: string | null | undefined): string {
    if (!iso) return '—';
    try {
      const d = new Date(iso);
      if (isNaN(d.getTime())) return iso;
      const day = String(d.getDate()).padStart(2, '0');
      const mon = String(d.getMonth() + 1).padStart(2, '0');
      const y = d.getFullYear();
      const h = String(d.getHours()).padStart(2, '0');
      const m = String(d.getMinutes()).padStart(2, '0');
      return `${day}.${mon}.${y} ${h}:${m}`;
    } catch {
      return iso;
    }
  }
}
