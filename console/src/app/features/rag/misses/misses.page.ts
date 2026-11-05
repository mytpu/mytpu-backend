import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { PlaygroundApi } from '../playground/playground.api';
import { CandidateItem } from '../playground/playground.model';
import { MissesApi } from './misses.api';
import { MissGroupItem } from './misses.model';

@Component({
  selector: 'app-misses-page',
  standalone: true,
  imports: [
    FormsModule,
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    SkeletonComponent,
  ],
  templateUrl: './misses.page.html',
  styleUrl: './misses.page.css',
})
export class MissesPage implements OnInit {
  private readonly api = inject(MissesApi);
  private readonly playgroundApi = inject(PlaygroundApi);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);

  readonly days = signal<number>(7);
  readonly groups = signal<MissGroupItem[]>([]);
  readonly selectedGroup = signal<MissGroupItem | null>(null);
  readonly nearCandidate = signal<CandidateItem | null>(null);
  readonly nearLoading = signal<boolean>(false);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly addedSuccess = signal<boolean>(false);

  ngOnInit(): void {
    this.load(true);
  }

  load(showLoading = false): void {
    if (showLoading) this.loading.set(true);
    this.api
      .getMisses(this.days())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.groups.set(items);
          if (items.length > 0) {
            const current = this.selectedGroup();
            const exists = current && items.some((x) => x.query === current.query);
            if (!exists) {
              this.selectGroup(items[0]);
            }
          } else {
            this.selectedGroup.set(null);
            this.nearCandidate.set(null);
          }
          this.loading.set(false);
          this.error.set(null);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.misses.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  reload(): void {
    this.load(true);
  }

  onDaysChange(d: number | string): void {
    this.days.set(Number(d));
    this.load(true);
  }

  selectGroup(g: MissGroupItem): void {
    this.selectedGroup.set(g);
    this.loadNearCandidate(g.query);
  }

  private nearRequest?: Subscription;

  private loadNearCandidate(query: string): void {
    // Прошлый запрос отменяется: иначе его ответ, пришедший позже, покажет кусок чужой группы
    this.nearRequest?.unsubscribe();
    this.nearLoading.set(true);
    this.nearCandidate.set(null);
    this.nearRequest = this.playgroundApi
      .probe({ query, access: 'public' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ans) => {
          this.nearCandidate.set(ans.candidates[0] || null);
          this.nearLoading.set(false);
        },
        error: () => {
          this.nearCandidate.set(null);
          this.nearLoading.set(false);
        },
      });
  }

  formatSimilarity(v?: number | null): string {
    if (v === undefined || v === null) return '—';
    return v.toFixed(2).replace('.', ',');
  }

  goToUpload(): void {
    this.router.navigate(['/rag/sources']);
  }

  checkInPlayground(query: string): void {
    this.router.navigate(['/rag/playground'], { queryParams: { q: query } });
  }

  addToEval(query: string): void {
    this.api.addToEval(query).subscribe({
      next: () => {
        this.addedSuccess.set(true);
        setTimeout(() => this.addedSuccess.set(false), 3000);
      },
      error: () => this.error.set(this.i18n.t('rag.misses.errorAddToEval')),
    });
  }

  formatShortDate(iso?: string): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
  }
}
