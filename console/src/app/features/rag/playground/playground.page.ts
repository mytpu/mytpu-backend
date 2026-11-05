import {
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { PlaygroundApi } from './playground.api';
import { PlaygroundAnswer } from './playground.model';

@Component({
  selector: 'app-playground-page',
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
  ],
  templateUrl: './playground.page.html',
  styleUrl: './playground.page.css',
})
export class PlaygroundPage implements OnInit {
  private readonly api = inject(PlaygroundApi);
  private readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  readonly query = signal<string>('');
  readonly access = signal<string>('public');
  readonly answer = signal<PlaygroundAnswer | null>(null);
  readonly loading = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const qParam = this.route.snapshot.queryParamMap.get('q');
    if (qParam) {
      this.query.set(qParam);
      this.search();
    }
  }

  search(): void {
    const q = this.query().trim();
    if (!q) return;
    this.loading.set(true);
    this.error.set(null);
    this.api
      .probe({ query: q, access: this.access() })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ans) => {
          this.answer.set(ans);
          this.loading.set(false);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.playground.errorSearch'));
          this.loading.set(false);
        },
      });
  }

  countAboveThreshold(ans: PlaygroundAnswer): number {
    const threshold = ans.thresholds.softSimilarity;
    return ans.candidates.filter((c) => (c.similarity || 0) >= threshold).length;
  }

  reasonLabel(reason: string): string {
    switch (reason) {
      case 'no_candidates': return this.i18n.t('rag.playground.reason_no_candidates');
      case 'below_soft_threshold': return this.i18n.t('rag.playground.reason_below_soft_threshold');
      case 'below_threshold_small_margin': return this.i18n.t('rag.playground.reason_below_threshold_small_margin');
      default: return reason;
    }
  }

  formatScore(score?: number | null): string {
    if (score === undefined || score === null) return '—';
    return score.toFixed(2).replace('.', ',');
  }
}
