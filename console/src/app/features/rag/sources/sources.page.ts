import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin, interval } from 'rxjs';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent, BadgeKind } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { SourceDeleteDialogComponent } from './source-delete-dialog.component';
import { SourceDropzoneComponent } from './source-dropzone.component';
import { SourcesApi } from './sources.api';
import { JobItem, SourceItem } from './sources.model';

@Component({
  selector: 'app-sources-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    SkeletonComponent,
    SourceDropzoneComponent,
    SourceDeleteDialogComponent,
  ],
  templateUrl: './sources.page.html',
  styleUrl: './sources.page.css',
})
export class SourcesPage implements OnInit {
  private readonly api = inject(SourcesApi);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);

  readonly sources = signal<SourceItem[]>([]);
  readonly jobs = signal<JobItem[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly sourceToDelete = signal<SourceItem | null>(null);

  ngOnInit(): void {
    this.load(true);
    interval(2000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const hasActiveJobs = this.jobs().some((j) => j.status === 'running' || j.status === 'queued');
        if (hasActiveJobs) {
          this.pollJobs();
        }
      });
  }

  load(showLoading = false): void {
    if (showLoading) this.loading.set(true);
    forkJoin({
      sources: this.api.list(),
      jobs: this.api.getJobs(10),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.sources.set(res.sources);
          this.jobs.set(res.jobs);
          this.loading.set(false);
          this.error.set(null);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.sources.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  private pollJobs(): void {
    this.api
      .getJobs(10)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (jobs) => {
          this.jobs.set(jobs);
          this.api.list().subscribe((srcs) => this.sources.set(srcs));
        },
      });
  }

  reload(): void {
    this.load(true);
  }

  onSourceCreated(res: { sourceId: number; jobId: number }): void {
    this.load(false);
  }

  reindexAll(): void {
    for (const s of this.sources()) {
      this.reindexSource(s);
    }
  }

  reindexSource(s: SourceItem): void {
    this.api.reindex(s.id).subscribe({
      next: () => this.pollJobs(),
      error: () => this.error.set(this.i18n.t('rag.sources.errorReindex')),
    });
  }

  promptDelete(s: SourceItem): void {
    this.sourceToDelete.set(s);
  }

  confirmDelete(): void {
    const s = this.sourceToDelete();
    if (!s) return;
    this.api.delete(s.id).subscribe({
      next: () => {
        this.sourceToDelete.set(null);
        this.load(false);
      },
      error: () => this.error.set(this.i18n.t('rag.sources.errorDelete')),
    });
  }

  tagLabel(kind: string): string {
    switch (kind) {
      case 'site': return this.i18n.t('rag.sources.tagSite');
      case 'base': return this.i18n.t('rag.sources.tagBase');
      default: return this.i18n.t('rag.sources.tagConsole');
    }
  }

  formatShortDate(iso?: string): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
  }

  jobBadgeKind(status: string): BadgeKind {
    switch (status) {
      case 'done': return 'ok';
      case 'running': return 'info';
      case 'failed': return 'danger';
      default: return 'neutral';
    }
  }

  jobStatusLabel(status: string): string {
    switch (status) {
      case 'done': return this.i18n.t('rag.sources.jobStatusDone');
      case 'running': return this.i18n.t('rag.sources.jobStatusRunning');
      case 'failed': return this.i18n.t('rag.sources.jobStatusFailed');
      case 'queued': return this.i18n.t('rag.sources.jobStatusQueued');
      default: return status;
    }
  }

  stageActive(status: string, idx: number): boolean {
    if (status === 'done') return true;
    if (status === 'failed') return idx === 1;
    if (status === 'running') return idx <= 3;
    if (status === 'queued') return idx <= 1;
    return false;
  }
}
