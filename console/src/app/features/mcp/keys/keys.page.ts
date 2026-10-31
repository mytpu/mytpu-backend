import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { TableComponent, TableColumn } from '../../../ui/table/table.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { KeyCreatedDialogComponent } from '../key-created/key-created-dialog.component';
import { KeysApi } from './keys.api';
import { IssuedKey, KeyView, ResumeRequest } from './keys.model';
import { KeysFilterComponent } from './keys-filter.component';
import { ReissueDialogComponent } from './reissue-dialog.component';
import { RevokeDialogComponent } from './revoke-dialog.component';
import { ResumeDialogComponent } from './resume-dialog.component';

@Component({
  selector: 'app-keys-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    IconComponent,
    TableComponent,
    SkeletonComponent,
    EmptyStateComponent,
    KeyCreatedDialogComponent,
    KeysFilterComponent,
    ReissueDialogComponent,
    RevokeDialogComponent,
    ResumeDialogComponent,
  ],
  templateUrl: './keys.page.html',
  styleUrl: './keys.page.css',
})
export class KeysPage implements OnInit {
  private readonly api = inject(KeysApi);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  readonly sessionStore = inject(SessionStore);

  readonly keys = signal<KeyView[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly searchQuery = signal<string>('');
  readonly statusFilter = signal<'active' | 'expiring' | 'revoked'>('active');
  readonly scopeFilter = signal<string>('all');

  readonly reissuingKey = signal<KeyView | null>(null);
  readonly revokingKey = signal<KeyView | null>(null);
  readonly resumingKey = signal<KeyView | null>(null);
  readonly createdKey = signal<IssuedKey | null>(null);
  readonly createdKeyOwner = signal<string>('');
  readonly submitting = signal<boolean>(false);
  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly columns: TableColumn[] = [
    { title: this.i18n.t('mcp.keys.colOwner') },
    { title: this.i18n.t('mcp.keys.colKey'), width: '100px' },
    { title: this.i18n.t('mcp.keys.colApis'), width: '160px' },
    { title: this.i18n.t('mcp.keys.colExpires'), width: '130px' },
    { title: this.i18n.t('mcp.keys.colActivity'), width: '100px' },
    { title: this.i18n.t('mcp.keys.colCalls'), width: '70px', align: 'right' },
    { title: this.i18n.t('mcp.keys.colGeo'), width: '120px' },
    { title: '', width: '130px', align: 'right' },
  ];

  readonly activeCount = computed(() => {
    const now = Date.now();
    return this.keys().filter((k) => k.enabled && !k.suspended && new Date(k.expiresAt).getTime() > now).length;
  });

  readonly expiringCount = computed(() => {
    const now = Date.now();
    return this.keys().filter((k) => {
      const exp = new Date(k.expiresAt).getTime();
      return k.enabled && !k.suspended && exp > now && exp - now <= 7 * 86400000;
    }).length;
  });

  readonly revokedCount = computed(() => {
    const now = Date.now();
    return this.keys().filter((k) => !k.enabled || k.suspended || new Date(k.expiresAt).getTime() <= now).length;
  });

  readonly allScopes = computed(() => {
    const set = new Set<string>();
    for (const k of this.keys()) for (const s of k.scopes) set.add(s);
    return Array.from(set);
  });

  readonly filteredKeys = computed(() => {
    const now = Date.now();
    const q = this.searchQuery().trim().toLowerCase();
    const st = this.statusFilter();
    const sc = this.scopeFilter();

    return this.keys().filter((k) => {
      const exp = new Date(k.expiresAt).getTime();
      const isActive = k.enabled && !k.suspended && exp > now;
      const isExpiring = isActive && exp - now <= 7 * 86400000;
      const isRevoked = !k.enabled || k.suspended || exp <= now;

      if (st === 'active' && !isActive) return false;
      if (st === 'expiring' && !isExpiring) return false;
      if (st === 'revoked' && !isRevoked) return false;
      if (sc !== 'all' && !k.scopes.includes(sc)) return false;
      if (q) return k.owner.toLowerCase().includes(q) || k.keyPrefix.toLowerCase().includes(q);
      return true;
    });
  });

  ngOnInit(): void {
    this.loadKeys();
  }

  loadKeys(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (d) => { this.keys.set(d); this.loading.set(false); },
      error: () => { this.error.set(this.i18n.t('mcp.keys.error')); this.loading.set(false); },
    });
  }

  onNewKey(): void {
    if (this.canChange()) this.router.navigate(['/mcp/keys/new']);
  }

  onEditPermissions(k: KeyView): void {
    if (this.canChange()) this.router.navigate(['/mcp/keys/new'], { queryParams: { edit: k.id } });
  }

  submitReissue(graceHours: number): void {
    const k = this.reissuingKey();
    if (!k) return;
    this.submitting.set(true);
    this.api.reissue(k.id, graceHours).subscribe({
      next: (issued) => {
        this.submitting.set(false);
        this.reissuingKey.set(null);
        this.createdKeyOwner.set(k.owner);
        this.createdKey.set(issued);
        this.loadKeys();
      },
      error: () => this.submitting.set(false),
    });
  }

  submitRevoke(): void {
    const k = this.revokingKey();
    if (!k) return;
    this.submitting.set(true);
    this.api.revoke(k.id).subscribe({
      next: () => { this.submitting.set(false); this.revokingKey.set(null); this.loadKeys(); },
      error: () => this.submitting.set(false),
    });
  }

  submitResume(req: ResumeRequest): void {
    const k = this.resumingKey();
    if (!k) return;
    this.submitting.set(true);
    this.api.resume(k.id, req).subscribe({
      next: () => { this.submitting.set(false); this.resumingKey.set(null); this.loadKeys(); },
      error: () => this.submitting.set(false),
    });
  }

  onKeySaved(): void {
    this.createdKey.set(null);
    this.createdKeyOwner.set('');
  }

  formatDate(iso: string): string {
    return new Date(iso).toLocaleDateString('ru-RU');
  }

  daysUntil(iso: string): number {
    return Math.max(1, Math.ceil((new Date(iso).getTime() - Date.now()) / 86400000));
  }
}
