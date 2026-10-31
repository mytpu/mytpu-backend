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
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly searchQuery = signal<string>('');
  readonly statusFilter = signal<RequestStatus>('new');
  readonly kindFilter = signal<string>('all');
  readonly submitting = signal<boolean>(false);

  readonly showCreateDialog = signal<boolean>(false);
  readonly showRejectDialog = signal<boolean>(false);
  readonly createdKey = signal<IssuedKey | null>(null);
  readonly createdKeyOwner = signal<string>('');

  readonly canChange = computed(() => this.sessionStore.canChange());

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
      if (q) {
        const text = `${r.requester} ${r.contact} ${r.purpose}`.toLowerCase();
        return text.includes(q);
      }
      return true;
    });
  });

  readonly decidedThisWeek = computed(() => {
    const now = Date.now();
    return this.requests().filter((r) => {
      if (r.status === 'new') return false;
      if (!r.decidedAt) return true;
      const t = new Date(r.decidedAt).getTime();
      return now - t <= 7 * 86400000;
    });
  });

  readonly selectedRequest = computed(() => {
    const id = this.selectedId();
    if (!id) return this.filteredRequests()[0] ?? this.requests()[0] ?? null;
    return this.requests().find((r) => r.id === id) ?? this.filteredRequests()[0] ?? null;
  });

  ngOnInit(): void {
    this.loadRequests();
  }

  loadRequests(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (data) => {
        this.requests.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(this.i18n.t('mcp.requests.error'));
        this.loading.set(false);
      },
    });
  }

  selectRequest(req: KeyRequestView): void {
    this.selectedId.set(req.id);
  }

  handleCreate(body: CreateKeyRequestBody): void {
    this.submitting.set(true);
    this.api.create(body).subscribe({
      next: (created) => {
        this.submitting.set(false);
        this.showCreateDialog.set(false);
        this.loadRequests();
        this.selectedId.set(created.id);
      },
      error: () => this.submitting.set(false),
    });
  }

  handleApprove(body: ApproveBody): void {
    const req = this.selectedRequest();
    if (!req) return;
    this.createdKeyOwner.set(req.requester);
    this.submitting.set(true);
    this.api.approve(req.id, body).subscribe({
      next: (issued) => {
        this.submitting.set(false);
        this.createdKey.set(issued);
        this.loadRequests();
      },
      error: () => this.submitting.set(false),
    });
  }

  handleReject(reason: string): void {
    const req = this.selectedRequest();
    if (!req) return;
    this.submitting.set(true);
    this.api.reject(req.id, { reason }).subscribe({
      next: () => {
        this.submitting.set(false);
        this.showRejectDialog.set(false);
        this.loadRequests();
      },
      error: () => this.submitting.set(false),
    });
  }

  formatDate(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('ru-RU');
  }

  formatTime(iso: string): string {
    const d = new Date(iso);
    return `${d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })} ${d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`;
  }
}
