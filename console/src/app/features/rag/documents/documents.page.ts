import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { ToggleComponent } from '../../../ui/toggle/toggle.component';
import { DocumentDeleteDialogComponent } from './document-delete-dialog.component';
import { DocumentsApi } from './documents.api';
import { DocumentRow } from './documents.model';

@Component({
  selector: 'app-documents-page',
  standalone: true,
  imports: [
    FormsModule,
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    IconComponent,
    SkeletonComponent,
    ToggleComponent,
    DocumentDeleteDialogComponent,
  ],
  templateUrl: './documents.page.html',
  styleUrl: './documents.page.css',
})
export class DocumentsPage implements OnInit {
  private readonly api = inject(DocumentsApi);
  private readonly i18n = inject(I18n);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);

  readonly items = signal<DocumentRow[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);

  readonly searchQuery = signal<string>('');
  readonly statusFilter = signal<string>('active');
  readonly docToDelete = signal<DocumentRow | null>(null);

  ngOnInit(): void {
    this.load(true);
  }

  load(showLoading = false): void {
    if (showLoading) this.loading.set(true);
    this.api
      .list({
        q: this.searchQuery(),
        status: this.statusFilter(),
        limit: 100,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.items.set(rows);
          this.loading.set(false);
          this.error.set(null);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.documents.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  reload(): void {
    this.load(true);
  }

  onSearchChange(q: string): void {
    this.searchQuery.set(q);
    this.load(false);
  }

  setStatusFilter(status: string): void {
    this.statusFilter.set(status);
    this.load(false);
  }

  resetFilters(): void {
    this.searchQuery.set('');
    this.statusFilter.set('all');
    this.load(true);
  }

  openDoc(id: number): void {
    this.router.navigate(['/rag/documents', id]);
  }

  toggleStatus(doc: DocumentRow): void {
    const nextStatus = doc.status === 'active' ? 'disabled' : 'active';
    this.api.updateStatus(doc.id, nextStatus).subscribe({
      next: (updated) => {
        this.items.update((list) =>
          list.map((x) => (x.id === doc.id ? { ...x, status: updated.status } : x)),
        );
      },
      error: () => this.error.set(this.i18n.t('rag.documents.errorToggleStatus')),
    });
  }

  promptDelete(doc: DocumentRow): void {
    this.docToDelete.set(doc);
  }

  confirmDelete(): void {
    const doc = this.docToDelete();
    if (!doc) return;
    this.api.delete(doc.id).subscribe({
      next: () => {
        this.docToDelete.set(null);
        this.items.update((list) => list.filter((x) => x.id !== doc.id));
      },
      error: () => this.error.set(this.i18n.t('rag.documents.errorDelete')),
    });
  }

  sourceName(sourceId: number): string {
    switch (sourceId) {
      case 1: return 'tpu.ru';
      case 2: return this.i18n.t('rag.documents.sourceBaseTpu');
      default: return this.i18n.t('rag.documents.sourceConsole');
    }
  }

  formatShortDate(iso?: string): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
  }
}
