import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SessionStore } from '../../../core/session/session.store';
import { CardComponent } from '../../../ui/card/card.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { IntegrationsApi } from './integrations.api';
import { IntegrationView, LimitsChange, ToolView } from './integrations.model';
import { IntegrationsTreeComponent } from './integrations-tree.component';
import { ToolLimitsCardComponent } from './tool-limits-card.component';
import { DisableConfirmDialogComponent } from './disable-confirm-dialog.component';
import { CountdownToastComponent } from './countdown-toast.component';

interface PendingDisable {
  type: 'integration' | 'tool';
  id: string;
  name: string;
}

@Component({
  selector: 'app-integrations-page',
  standalone: true,
  imports: [
    FormsModule,
    TPipe,
    CardComponent,
    EmptyStateComponent,
    IconComponent,
    SkeletonComponent,
    IntegrationsTreeComponent,
    ToolLimitsCardComponent,
    DisableConfirmDialogComponent,
    CountdownToastComponent,
  ],
  templateUrl: './integrations.page.html',
  styleUrl: './integrations.page.css',
})
export class IntegrationsPage implements OnInit {
  private readonly api = inject(IntegrationsApi);
  readonly sessionStore = inject(SessionStore);

  readonly items = signal<IntegrationView[]>([]);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly searchQuery = signal<string>('');
  readonly selectedTool = signal<ToolView | null>(null);
  readonly selectedIntegration = signal<IntegrationView['integration'] | null>(null);
  readonly savingLimits = signal<boolean>(false);

  readonly pendingDisable = signal<PendingDisable | null>(null);
  readonly submittingDisable = signal<boolean>(false);
  readonly disabledToastItem = signal<{ name: string; type: 'integration' | 'tool'; id: string } | null>(null);

  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly filteredItems = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const all = this.items();
    if (!q) return all;
    return all
      .map((item) => ({
        ...item,
        tools: item.tools.filter((t) => t.name.toLowerCase().includes(q) || t.description.toLowerCase().includes(q)),
      }))
      .filter((item) => item.tools.length > 0 || item.integration.title.toLowerCase().includes(q));
  });

  ngOnInit(): void {
    this.loadCatalog();
  }

  loadCatalog(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (res) => {
        this.items.set(res);
        this.loading.set(false);
        this.autoSelectDefaultTool(res);
      },
      error: (err) => {
        this.error.set(err.message || 'Error');
        this.loading.set(false);
      },
    });
  }

  private autoSelectDefaultTool(list: IntegrationView[]): void {
    if (this.selectedTool()) return;
    for (const item of list) {
      const raspWeek = item.tools.find((t) => t.name === 'rasp.week');
      if (raspWeek) {
        this.selectedTool.set(raspWeek);
        this.selectedIntegration.set(item.integration);
        return;
      }
    }
    if (list.length && list[0].tools.length) {
      this.selectedTool.set(list[0].tools[0]);
      this.selectedIntegration.set(list[0].integration);
    }
  }

  onToolSelect(event: { tool: ToolView; integration: IntegrationView['integration'] }): void {
    this.selectedTool.set(event.tool);
    this.selectedIntegration.set(event.integration);
  }

  onIntegToggle(event: { integration: IntegrationView['integration']; enabled: boolean }): void {
    if (event.enabled) {
      this.api.updateIntegrationState(event.integration.id, { enabled: true }).subscribe({
        next: (updated) => this.replaceIntegration(updated),
      });
    } else {
      this.pendingDisable.set({ type: 'integration', id: event.integration.id, name: event.integration.title });
    }
  }

  onToolToggle(event: { tool: ToolView; enabled: boolean }): void {
    if (event.enabled) {
      this.api.updateToolState(event.tool.name, { enabled: true }).subscribe({
        next: (updated) => this.replaceTool(updated),
      });
    } else {
      this.pendingDisable.set({ type: 'tool', id: event.tool.name, name: event.tool.name });
    }
  }

  confirmDisable(reason: string): void {
    const p = this.pendingDisable();
    if (!p) return;
    this.submittingDisable.set(true);
    this.setEnabled(p.type, p.id, false, reason, () => {
      this.submittingDisable.set(false);
      this.pendingDisable.set(null);
      this.disabledToastItem.set({ name: p.name, type: p.type, id: p.id });
    }, () => this.submittingDisable.set(false));
  }

  undoDisable(): void {
    const item = this.disabledToastItem();
    if (item) this.setEnabled(item.type, item.id, true, undefined, () => this.disabledToastItem.set(null));
  }

  onSaveLimits(change: LimitsChange): void {
    const t = this.selectedTool();
    if (!t) return;
    this.savingLimits.set(true);
    this.api.updateToolLimits(t.name, change).subscribe({
      next: (updated) => {
        this.savingLimits.set(false);
        this.replaceTool(updated);
      },
      error: () => this.savingLimits.set(false),
    });
  }

  onResetLimits(): void {
    this.onSaveLimits({ cacheTtlSeconds: null, timeoutMs: null, ratePerMinute: null });
  }

  /** Включить или выключить интеграцию либо инструмент и обновить строку в дереве. */
  private setEnabled(type: PendingDisable['type'], id: string, enabled: boolean, reason: string | undefined,
                     done: () => void, failed?: () => void): void {
    const body = reason === undefined ? { enabled } : { enabled, reason };
    if (type === 'integration') {
      this.api.updateIntegrationState(id, body).subscribe({
        next: (updated) => { this.replaceIntegration(updated); done(); },
        error: () => failed?.(),
      });
    } else {
      this.api.updateToolState(id, body).subscribe({
        next: (updated) => { this.replaceTool(updated); done(); },
        error: () => failed?.(),
      });
    }
  }

  private replaceIntegration(updated: IntegrationView): void {
    this.items.update((list) => list.map((i) => (i.integration.id === updated.integration.id ? updated : i)));
    if (this.selectedIntegration()?.id === updated.integration.id) {
      this.selectedIntegration.set(updated.integration);
    }
  }

  private replaceTool(updated: ToolView): void {
    this.items.update((list) =>
      list.map((i) => ({
        ...i,
        tools: i.tools.map((t) => (t.name === updated.name ? updated : t)),
      })),
    );
    if (this.selectedTool()?.name === updated.name) {
      this.selectedTool.set(updated);
    }
  }
}
