import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18n } from '../../../core/i18n/i18n.service';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SessionStore } from '../../../core/session/session.store';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { TableComponent } from '../../../ui/table/table.component';
import { ModeApi } from './mode.api';
import { ModeHistoryItem, ModeView } from './mode.model';
import { ModeCardsComponent } from './mode-cards.component';
import { ModeDialogComponent } from './mode-dialog.component';

@Component({
  selector: 'app-mode-page',
  standalone: true,
  imports: [
    FormsModule,
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    EmptyStateComponent,
    SkeletonComponent,
    TableComponent,
    ModeCardsComponent,
    ModeDialogComponent,
  ],
  templateUrl: './mode.page.html',
  styleUrl: './mode.page.css',
})
export class ModePage implements OnInit {
  private readonly api = inject(ModeApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly i18n = inject(I18n);
  readonly sessionStore = inject(SessionStore);

  readonly modeData = signal<ModeView | null>(null);
  readonly history = signal<ModeHistoryItem[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);

  readonly selectedTargetMode = signal<string>('normal');
  readonly draftReason = signal<string>('');
  readonly showConfirmDialog = signal<boolean>(false);
  readonly submitting = signal<boolean>(false);

  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly columns = [
    { title: this.i18n.t('mcp.mode.colWhen'), width: '130px' },
    { title: this.i18n.t('mcp.mode.colMode'), width: '140px' },
    { title: this.i18n.t('mcp.mode.colReason') },
    { title: this.i18n.t('mcp.mode.colWho'), width: '120px' },
  ];

  ngOnInit(): void {
    this.loadData();
    const timer = setInterval(() => this.pollData(), 30000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  loadData(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.getMode().subscribe({
      next: (m) => {
        this.modeData.set(m);
        this.selectedTargetMode.set(m.mode === 'normal' ? 'read_only' : 'normal');
        this.loadHistory();
      },
      error: (err) => {
        this.error.set(err.message || 'Error');
        this.loading.set(false);
      },
    });
  }

  private pollData(): void {
    // Background polling: preserves draft inputs without resetting
    this.api.getMode().subscribe({
      next: (m) => this.modeData.set(m),
    });
    this.api.getHistory().subscribe({
      next: (h) => this.history.set(h),
    });
  }

  private loadHistory(): void {
    this.api.getHistory().subscribe({
      next: (h) => {
        this.history.set(h);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  onModeSelect(modeId: string): void {
    if (modeId !== this.modeData()?.mode) {
      this.selectedTargetMode.set(modeId);
    }
  }

  openChangeDialog(): void {
    this.showConfirmDialog.set(true);
  }

  confirmModeChange(reason: string): void {
    this.submitting.set(true);
    this.api.changeMode({ mode: this.selectedTargetMode(), reason }).subscribe({
      next: (res) => {
        this.modeData.set(res);
        this.submitting.set(false);
        this.showConfirmDialog.set(false);
        this.draftReason.set('');
        this.loadHistory();
      },
      error: () => this.submitting.set(false),
    });
  }

  formatDate(iso: string): string {
    const d = new Date(iso);
    const day = String(d.getDate()).padStart(2, '0');
    const mon = String(d.getMonth() + 1).padStart(2, '0');
    const h = String(d.getHours()).padStart(2, '0');
    const m = String(d.getMinutes()).padStart(2, '0');
    return `${day}.${mon} ${h}:${m}`;
  }
}
