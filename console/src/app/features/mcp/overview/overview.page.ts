import {
  Component,
  computed,
  DestroyRef,
  inject,
  linkedSignal,
  OnInit,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { getOverviewResource } from './overview.api';
import { LiveNumComponent } from './live-num.component';
import { NowTileComponent } from './now-tile.component';
import { OverviewView, ToolStats } from './overview.model';

@Component({
  selector: 'app-overview-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    CardComponent,
    IconComponent,
    SkeletonComponent,
    EmptyStateComponent,
    LiveNumComponent,
    NowTileComponent,
  ],
  templateUrl: './overview.page.html',
  styleUrl: './overview.page.css',
})
export class OverviewPage implements OnInit {
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly hours = signal<number>(24);
  readonly resource = getOverviewResource(this.hours);

  readonly view = linkedSignal<OverviewView | undefined, OverviewView | undefined>({
    source: () => this.resource.value(),
    computation: (v, prev) => v ?? prev?.value,
  });

  readonly periodKey = computed(() => {
    switch (this.hours()) {
      case 168:
        return 'mcp.overview.period7d';
      case 720:
        return 'mcp.overview.period30d';
      default:
        return 'mcp.overview.period24h';
    }
  });

  readonly totalCalls = computed(() => {
    return this.view()?.stats.totals.calls ?? 0;
  });

  readonly totalErrors = computed(() => {
    return this.view()?.stats.totals.errors ?? 0;
  });

  readonly errorPercent = computed(() => {
    const calls = this.totalCalls();
    if (!calls) return '0,0';
    const percent = (this.totalErrors() / calls) * 100;
    return percent.toFixed(1).replace('.', ',');
  });

  readonly p95Overall = computed(() => {
    const tools = this.view()?.stats.tools ?? [];
    if (!tools.length) return '—';
    const maxP95 = Math.max(...tools.map((t) => t.p95Ms ?? 0));
    return maxP95 > 0 ? `${maxP95} мс` : '—';
  });

  readonly activeClientsCount = computed(() => {
    return this.view()?.stats.topClients.length ?? 0;
  });

  readonly maxClientCalls = computed(() => {
    const clients = this.view()?.stats.topClients ?? [];
    return clients.length ? Math.max(...clients.map((c) => c.calls), 1) : 1;
  });

  readonly slowestTools = computed<ToolStats[]>(() => {
    const tools = this.view()?.stats.tools ?? [];
    return [...tools]
      .filter((t) => (t.p95Ms ?? 0) > 0)
      .sort((a, b) => (b.p95Ms ?? 0) - (a.p95Ms ?? 0))
      .slice(0, 3);
  });

  ngOnInit(): void {
    const timer = setInterval(() => {
      this.resource.reload();
    }, 30000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  setPeriod(h: number): void {
    this.hours.set(h);
  }

  reload(): void {
    this.resource.reload();
  }

  goToIntegrations(): void {
    this.router.navigate(['/mcp/integrations']);
  }

  goToKeys(): void {
    this.router.navigate(['/mcp/keys']);
  }

  toolP95(toolName: string): string {
    const t = this.view()?.stats.tools.find((item) => item.tool === toolName);
    return t?.p95Ms ? `${t.p95Ms} мс` : '—';
  }

  toolCalls(toolName: string): string {
    const t = this.view()?.stats.tools.find((item) => item.tool === toolName);
    return t ? String(t.calls) : '—';
  }
}
