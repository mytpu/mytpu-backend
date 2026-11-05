import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { forkJoin, interval } from 'rxjs';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { RagOverviewApi } from './overview.api';
import {
  RagOverviewData,
  RagOverviewJob,
  RagOverviewMiss,
  RagOverviewSource,
} from './overview.model';

@Component({
  selector: 'app-rag-overview-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    IconComponent,
    SkeletonComponent,
  ],
  templateUrl: './overview.page.html',
  styleUrl: './overview.page.css',
})
export class RagOverviewPage implements OnInit {
  private readonly api = inject(RagOverviewApi);
  private readonly i18n = inject(I18n);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);

  readonly data = signal<RagOverviewData | null>(null);
  readonly sources = signal<RagOverviewSource[]>([]);
  readonly jobs = signal<RagOverviewJob[]>([]);
  readonly misses = signal<RagOverviewMiss[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load(true);
    interval(30000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load(false));
  }

  load(showLoading = false): void {
    if (showLoading) this.loading.set(true);
    forkJoin({
      overview: this.api.getOverview(),
      sources: this.api.getSources(),
      jobs: this.api.getJobs(5),
      misses: this.api.getMisses(7, 4),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.data.set(res.overview);
          this.sources.set(res.sources);
          this.jobs.set(res.jobs);
          this.misses.set(res.misses);
          this.loading.set(false);
          this.error.set(null);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.overview.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  reload(): void {
    this.load(true);
  }

  goToSources(): void {
    this.router.navigate(['/rag/sources']);
  }

  goToMisses(): void {
    this.router.navigate(['/rag/misses']);
  }

  goToDocuments(): void {
    this.router.navigate(['/rag/documents']);
  }

  formatChunks(n?: number): string {
    if (!n) return '0';
    return n.toLocaleString('ru-RU');
  }

  formatIndexSize(bytes?: number): string {
    if (!bytes) return this.i18n.t('rag.overview.indexEmpty');
    const mb = Math.round(bytes / 1024 / 1024);
    return this.i18n.t('rag.overview.indexMb', { mb });
  }

  formatHit5(v?: number): string {
    if (v === undefined || v === null) return '—';
    return v.toFixed(2).replace('.', ',');
  }

  formatLastIndexed(iso?: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return `${d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })}, ${d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`;
  }

  formatShortDate(iso?: string): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
  }

  sharePercent(chunks: number, totalChunks: number): number {
    if (!totalChunks) return 0;
    return Math.min(100, Math.round((chunks / totalChunks) * 100));
  }
}
