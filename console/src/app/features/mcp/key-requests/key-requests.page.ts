import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { TableComponent, TableColumn } from '../../../ui/table/table.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { KeyCreatedDialogComponent } from '../key-created/key-created-dialog.component';
import { IssuedKey } from '../keys/keys.model';
import { IntegrationCatalogView } from '../key-wizard/key-wizard.model';
import { KeyRequestsApi } from './key-requests.api';
import { ApproveBody, CreateKeyRequestBody, KeyRequestView, RequestStatus } from './key-requests.model';
import { KeyRequestsFilterComponent } from './key-requests-filter.component';
import { KeyRequestsCardComponent } from './key-requests-card.component';
import { CreateRequestDialogComponent } from './create-request-dialog.component';
import { RejectRequestDialogComponent } from './reject-request-dialog.component';

@Component({
  selector: 'app-key-requests-page',
  standalone: true,
  imports: [
    TPipe, BadgeComponent, IconComponent, TableComponent, SkeletonComponent,
    EmptyStateComponent, KeyCreatedDialogComponent, KeyRequestsFilterComponent,
    KeyRequestsCardComponent, CreateRequestDialogComponent, RejectRequestDialogComponent,
  ],
  templateUrl: './key-requests.page.html',
  styleUrl: './key-requests.page.css',
})
export class KeyRequestsPage implements OnInit {
  private readonly api = inject(KeyRequestsApi);
  private readonly i18n = inject(I18n);
  readonly sessionStore = inject(SessionStore);

  readonly requests = signal<KeyRequestView[]>([]);
  readonly catalog = signal<IntegrationCatalogView[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly searchQuery = signal<string>('');
  readonly statusFilter = signal<RequestStatus>('new');
  readonly kindFilter = signal<string>('all');
  readonly submitting = signal<boolean>(false);

  readonly showCreateDialog = signal<boolean>(false);
  readonly showRejectDialog = signal<boolean>(false);
  readonly createServerError = signal<string | null>(null);
  readonly approveServerError = signal<string | null>(null);
  readonly rejectServerError = signal<string | null>(null);
  readonly createdKey = signal<IssuedKey | null>(null);
  readonly createdKeyOwner = signal<string>('');

  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly availableScopes = computed(() => {
    const list: string[] = [];
    for (const item of this.catalog()) {
      for (const s of item.integration.scopes) {
        if (!list.includes(s.name)) list.push(s.name);
      }
    }
    return list.length ? list : ['docs.read', 'rasp.read'];
  });

  readonly columns: TableColumn[] = [
    { title: this.i18n.t('mcp.requests.colRequesterPurpose') },
    { title: this.i18n.t('mcp.requests.colServices'), width: '120px' },
    { title: this.i18n.t('mcp.requests.colTypeDays'), width: '130px' },
    { title: this.i18n.t('mcp.requests.colArrived'), width: '110px' },
  ];

  readonly decidedColumns: TableColumn[] = [
    { title: this.i18n.t('mcp.requests.colRequesterPurpose') },
    { title: this.i18n.t('mcp.requests.colDecided'), width: '110px' },
    { title: this.i18n.t('mcp.requests.colIssued'), width: '130px' },
    { title: this.i18n.t('mcp.requests.colWhen'), width: '90px' },
  ];

  readonly newCount = computed(() => this.requests().filter((r) => r.status === 'new').length);
  readonly approvedCount = computed(() => this.requests().filter((r) => r.status === 'approved').length);
  readonly rejectedCount = computed(() => this.requests().filter((r) => r.status === 'rejected').length);

  readonly filteredRequests = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const st = this.statusFilter();
    const k = this.kindFilter();
    return this.requests().filter((r) => {
      if (r.status !== st) return false;
      if (k !== 'all' && r.kind !== k) return false;
      return !q || `${r.requester} ${r.contact} ${r.purpose}`.toLowerCase().includes(q);
    });
  });

  readonly decidedThisWeek = computed(() => {
    const now = Date.now();
    return this.requests().filter((r) => r.status !== 'new' && (!r.decidedAt || now - new Date(r.decidedAt).getTime() <= 7 * 86400000));
  });

  readonly selectedRequest = computed(() => {
    const id = this.selectedId();
    if (!id) return this.filteredRequests()[0] ?? this.requests()[0] ?? null;
    return this.requests().find((r) => r.id === id) ?? this.filteredRequests()[0] ?? null;
  });

  ngOnInit(): void {
    this.loadRequests();
    this.api.catalog().subscribe({ next: (cat) => this.catalog.set(cat) });
  }

  loadRequests(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (data) => { this.requests.set(data); this.loading.set(false); },
      error: () => { this.error.set(this.i18n.t('mcp.requests.error')); this.loading.set(false); },
    });
  }

  selectRequest(req: KeyRequestView): void {
    this.selectedId.set(req.id);
    this.approveServerError.set(null);
  }

  openCreateDialog(): void {
    this.createServerError.set(null);
    this.showCreateDialog.set(true);
  }

  openRejectDialog(): void {
    this.rejectServerError.set(null);
    this.showRejectDialog.set(true);
  }

  handleCreate(body: CreateKeyRequestBody): void {
    this.createServerError.set(null);
    this.submitting.set(true);
    this.api.create(body).subscribe({
      next: (created) => {
        this.submitting.set(false);
        this.showCreateDialog.set(false);
        this.loadRequests();
        this.selectedId.set(created.id);
      },
      error: (err: { message?: string }) => {
        this.submitting.set(false);
        this.createServerError.set(err.message || 'Ошибка сервера при создании');
      },
    });
  }

  handleApprove(body: ApproveBody): void {
    const req = this.selectedRequest();
    if (!req) return;
    this.approveServerError.set(null);
    this.createdKeyOwner.set(req.requester);
    this.submitting.set(true);
    this.api.approve(req.id, body).subscribe({
      next: (issued) => {
        this.submitting.set(false);
        this.createdKey.set(issued);
        this.loadRequests();
      },
      error: (err: { message?: string }) => {
        this.submitting.set(false);
        this.approveServerError.set(err.message || 'Ошибка сервера при одобрении');
      },
    });
  }

  handleReject(reason: string): void {
    const req = this.selectedRequest();
    if (!req) return;
    this.rejectServerError.set(null);
    this.submitting.set(true);
    this.api.reject(req.id, { reason }).subscribe({
      next: () => {
        this.submitting.set(false);
        this.showRejectDialog.set(false);
        this.loadRequests();
      },
      error: (err: { message?: string }) => {
        this.submitting.set(false);
        this.rejectServerError.set(err.message || 'Ошибка сервера при отклонении');
      },
    });
  }

  formatDate(iso: string | null): string { return iso ? new Date(iso).toLocaleDateString('ru-RU') : '—'; }

  formatTime(iso: string): string {
    const d = new Date(iso);
    return `${d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })} ${d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`;
  }
}
