import {
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { DocumentApi } from './document.api';
import { ChunkItem, DocumentDetail, SectionItem } from './document.model';

// Формат данных rag (метки Redactor в текстах документов, не текст интерфейса)
const REDACTED_FIO = '[ФИО]';
const REDACTED_PHONE = '[ТЕЛЕФОН]';
const REDACTED_PATTERN = /(\[ФИО\]|\[ТЕЛЕФОН\])/g;

export interface ChunkTextPart {
  text: string;
  redacted: boolean;
}

@Component({
  selector: 'app-document-page',
  standalone: true,
  imports: [
    TPipe,
    ButtonComponent,
    CardComponent,
    DialogComponent,
    EmptyStateComponent,
    SkeletonComponent,
  ],
  templateUrl: './document.page.html',
  styleUrl: './document.page.css',
})
export class DocumentPage implements OnInit {
  private readonly api = inject(DocumentApi);
  private readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);

  readonly doc = signal<DocumentDetail | null>(null);
  readonly sections = signal<SectionItem[]>([]);
  readonly selectedSectionId = signal<string>('');
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly showDeleteDialog = signal<boolean>(false);

  readonly activeSection = computed(() => {
    const id = this.selectedSectionId();
    return this.sections().find((s) => s.id === id) || this.sections()[0] || null;
  });

  readonly activeChunks = computed<ChunkItem[]>(() => {
    return this.activeSection()?.chunks || [];
  });

  ngOnInit(): void {
    const idParam = this.route.snapshot.paramMap.get('id');
    const id = Number(idParam);
    if (!id) {
      this.error.set(this.i18n.t('rag.document.errorInvalidId'));
      this.loading.set(false);
      return;
    }
    this.load(id);
  }

  load(id: number): void {
    this.loading.set(true);
    this.api
      .get(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.doc.set(res.document);
          this.sections.set(res.sections);
          if (res.sections.length > 0 && !this.selectedSectionId()) {
            this.selectedSectionId.set(res.sections[0].id);
          }
          this.loading.set(false);
          this.error.set(null);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.document.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  reload(): void {
    const d = this.doc();
    if (d) this.load(d.id);
  }

  selectSection(id: string): void {
    this.selectedSectionId.set(id);
  }

  toggleStatus(): void {
    const d = this.doc();
    if (!d) return;
    const nextStatus = d.status === 'active' ? 'disabled' : 'active';
    this.api.update(d.id, { status: nextStatus }).subscribe({
      next: (updated) => {
        this.doc.update((curr) => (curr ? { ...curr, status: updated.status } : null));
      },
      error: () => this.error.set(this.i18n.t('rag.document.errorUpdateStatus')),
    });
  }

  changeAccess(access: string): void {
    const d = this.doc();
    if (!d) return;
    this.api.update(d.id, { access }).subscribe({
      next: (updated) => {
        this.doc.update((curr) => (curr ? { ...curr, access: updated.access } : null));
      },
      error: () => this.error.set(this.i18n.t('rag.document.errorUpdateAccess')),
    });
  }

  confirmDelete(): void {
    const d = this.doc();
    if (!d) return;
    this.api.delete(d.id).subscribe({
      next: () => {
        this.showDeleteDialog.set(false);
        this.router.navigate(['/rag/documents']);
      },
      error: () => this.error.set(this.i18n.t('rag.document.errorDelete')),
    });
  }

  sourceName(sourceId?: number): string {
    switch (sourceId) {
      case 1: return 'tpu.ru';
      case 2: return this.i18n.t('rag.document.sourceBaseTpu');
      default: return this.i18n.t('rag.document.sourceUploadConsole');
    }
  }

  splitChunkText(text: string): ChunkTextPart[] {
    if (!text) return [];
    const parts = text.split(REDACTED_PATTERN);
    return parts
      .filter((part) => part.length > 0)
      .map((part) => ({
        text: part,
        redacted: part === REDACTED_FIO || part === REDACTED_PHONE,
      }));
  }
}
