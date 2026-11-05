import {
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { EvalAddDialogComponent } from './eval-add-dialog.component';
import { EvalApi } from './eval.api';
import { EvalQuestionItem, EvalRunItem } from './eval.model';

@Component({
  selector: 'app-eval-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    SkeletonComponent,
    EvalAddDialogComponent,
  ],
  templateUrl: './eval.page.html',
  styleUrl: './eval.page.css',
})
export class EvalPage implements OnInit {
  private readonly api = inject(EvalApi);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);
  readonly Math = Math;

  readonly questions = signal<EvalQuestionItem[]>([]);
  readonly runs = signal<EvalRunItem[]>([]);
  readonly loading = signal<boolean>(true);
  readonly running = signal<boolean>(false);
  readonly error = signal<string | null>(null);
  readonly showAddDialog = signal<boolean>(false);

  readonly latestRun = computed<EvalRunItem | null>(() => {
    const list = this.runs();
    return list.length > 0 ? list[0] : null;
  });

  readonly prevRun = computed<EvalRunItem | null>(() => {
    const list = this.runs();
    return list.length > 1 ? list[1] : null;
  });

  readonly chartRuns = computed<EvalRunItem[]>(() => {
    return [...this.runs()].reverse();
  });

  ngOnInit(): void {
    this.load(true);
  }

  load(showLoading = false): void {
    if (showLoading) this.loading.set(true);
    forkJoin({
      questions: this.api.getQuestions(),
      runs: this.api.getRuns(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.questions.set(res.questions);
          this.runs.set(res.runs);
          this.loading.set(false);
          this.error.set(null);
        },
        error: () => {
          this.error.set(this.i18n.t('rag.eval.errorLoad'));
          this.loading.set(false);
        },
      });
  }

  reload(): void {
    this.load(true);
  }

  runEval(): void {
    this.running.set(true);
    this.api
      .startRun()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.running.set(false);
          this.load(false);
        },
        error: () => {
          this.running.set(false);
          this.error.set(this.i18n.t('rag.eval.errorRun'));
        },
      });
  }

  importQuestions(): void {
    this.api.importQuestions().subscribe({
      next: () => this.load(false),
      error: () => this.error.set(this.i18n.t('rag.eval.errorImport')),
    });
  }

  saveQuestion(item: EvalQuestionItem): void {
    this.api.addQuestion(item).subscribe({
      next: () => {
        this.showAddDialog.set(false);
        this.load(false);
      },
      error: () => this.error.set(this.i18n.t('rag.eval.errorSaveQuestion')),
    });
  }

  deleteQuestion(id: number): void {
    this.api.deleteQuestion(id).subscribe({
      next: () => this.load(false),
      error: () => this.error.set(this.i18n.t('rag.eval.errorDeleteQuestion')),
    });
  }

  formatScore(v?: number): string {
    if (v === undefined || v === null) return '—';
    return v.toFixed(2).replace('.', ',');
  }

  formatShortDate(iso?: string): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' });
  }
}
