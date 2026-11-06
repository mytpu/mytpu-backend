import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { SessionStore } from '../../../../core/session/session.store';
import { BadgeComponent } from '../../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { EmptyStateComponent } from '../../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../../ui/skeleton/skeleton.component';
import { TableColumn, TableComponent } from '../../../../ui/table/table.component';
import { ConnectorsApi } from '../connectors.api';
import { ConnectorListItem } from '../connectors.model';

@Component({
  selector: 'app-connectors-list-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    EmptyStateComponent,
    IconComponent,
    SkeletonComponent,
    TableComponent,
  ],
  templateUrl: './connectors-list.page.html',
  styleUrl: './connectors-list.page.css',
})
export class ConnectorsListPage implements OnInit {
  private readonly api = inject(ConnectorsApi);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  readonly sessionStore = inject(SessionStore);

  readonly items = signal<ConnectorListItem[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);

  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly columns: TableColumn[] = [
    { title: this.i18n.t('connectors.colName'), width: '260px' },
    { title: this.i18n.t('connectors.colPublished'), width: '150px' },
    { title: this.i18n.t('connectors.colLatest'), width: '110px' },
    { title: this.i18n.t('connectors.colUpdated'), width: '150px' },
    { title: this.i18n.t('connectors.colSecrets'), width: '220px' },
    { title: '', width: '40px', align: 'right' },
  ];

  ngOnInit(): void {
    this.loadConnectors();
  }

  loadConnectors(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (list) => {
        this.items.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.message || this.i18n.t('connectors.error'));
        this.loading.set(false);
      },
    });
  }

  openConnector(id: string): void {
    this.router.navigate(['/mcp/connectors', id]);
  }

  onNewConnector(): void {
    this.router.navigate(['/mcp/connectors/new']);
  }

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
