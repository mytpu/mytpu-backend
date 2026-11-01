import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { SessionStore } from '../../../core/session/session.store';
import { SkeletonComponent } from '../../../ui/skeleton/skeleton.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { KeyView, ResumeRequest } from '../keys/keys.model';
import { ResumeDialogComponent } from '../keys/resume-dialog.component';
import { KeyEventsApi } from './key-events.api';
import { ExpiringKeyView, KeyEventView, mergeDecoyKeys } from './key-events.model';
import { KeyEventsStatsComponent } from './key-events-stats.component';
import { KeyEventsAlarmComponent, SuspendedKeyInfo } from './key-events-alarm.component';
import { KeyEventsFeedComponent } from './key-events-feed.component';
import { KeyEventsSidebarComponent } from './key-events-sidebar.component';
import { AddDecoyMemberDialogComponent } from './add-decoy-member-dialog.component';

@Component({
  selector: 'app-key-events-page',
  standalone: true,
  imports: [
    TPipe, SkeletonComponent, EmptyStateComponent, KeyEventsStatsComponent,
    KeyEventsAlarmComponent, KeyEventsFeedComponent, KeyEventsSidebarComponent,
    ResumeDialogComponent, AddDecoyMemberDialogComponent,
  ],
  templateUrl: './key-events.page.html',
  styleUrl: './key-events.page.css',
})
export class KeyEventsPage implements OnInit {
  private readonly api = inject(KeyEventsApi);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);
  readonly sessionStore = inject(SessionStore);

  readonly events = signal<KeyEventView[]>([]);
  readonly expiringKeys = signal<ExpiringKeyView[]>([]);
  readonly allKeys = signal<KeyView[]>([]);
  readonly decoyGroupMembers = signal<string[]>([]);
  readonly selectedDecoyId = signal<string | null>(null);

  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly submitting = signal<boolean>(false);
  readonly highlightedIds = signal<Set<number>>(new Set());
  readonly highlightDecoyGroup = signal<boolean>(false);
  private readonly knownEventIds = new Set<number>();

  readonly eventTypeFilter = signal<string>('all');
  readonly searchQuery = signal<string>('');
  readonly showAddMemberDialog = signal<boolean>(false);
  readonly resumingKey = signal<{ id: string; owner: string } | null>(null);

  readonly canChange = computed(() => this.sessionStore.canChange());
  readonly decoyEvent = computed(() => this.events().find((e) => e.event === 'key.decoy_used') ?? null);
  readonly decoyCount = computed(() => this.events().filter((e) => e.event === 'key.decoy_used').length);
  readonly groupSuspendedCount = computed(() => this.events().filter((e) => e.event === 'key.group_suspended').length);
  readonly inactiveCount = computed(() => this.events().filter((e) => e.event === 'key.inactive_suspended').length);
  readonly otherSuspendedCount = computed(
    () => this.events().filter((e) => e.event === 'key.suspended' || e.event === 'key.foreign_country').length,
  );
  readonly suspendedCount = computed(() => this.groupSuspendedCount() + this.inactiveCount() + this.otherSuspendedCount());
  readonly selfRotatedCount = computed(() => this.events().filter((e) => e.event === 'key.self_rotated').length);
  readonly expiringCount = computed(() => this.expiringKeys().length);
  readonly decoyPlace = computed(() => this.decoyEvent()?.owner || '');

  readonly decoyKeys = computed(() => mergeDecoyKeys(this.allKeys(), this.events()));
  readonly availableKeysToAdd = computed(() => {
    const mems = new Set(this.decoyGroupMembers());
    return this.allKeys().filter((k) => !k.decoy && !mems.has(k.id));
  });

  readonly suspendedGroupKeys = computed<SuspendedKeyInfo[]>(() => {
    const keyMap = new Map<string, KeyView>();
    for (const k of this.allKeys()) keyMap.set(k.id, k);
    const dId = this.decoyEvent()?.clientId;
    const seen = new Set<string>();
    const list: SuspendedKeyInfo[] = [];
    for (const e of this.events()) {
      if (e.event === 'key.group_suspended' && (!dId || e.detail?.includes(`decoy=${dId}`)) && !seen.has(e.clientId)) {
        seen.add(e.clientId);
        const found = keyMap.get(e.clientId);
        list.push({ id: e.clientId, owner: e.owner || found?.owner || e.clientId, prefix: found?.keyPrefix || 'mtpu_…' });
      }
    }
    return list;
  });

  ngOnInit(): void {
    this.loadInitialData();
    const timer = setInterval(() => this.pollEvents(), 30000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  loadInitialData(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      events: this.api.getEvents(),
      expiring: this.api.getExpiring(),
      keys: this.api.getAllKeys(),
    }).subscribe({
      next: ({ events, expiring, keys }) => {
        for (const e of events) this.knownEventIds.add(e.id);
        this.events.set(events);
        this.expiringKeys.set(expiring);
        this.allKeys.set(keys);
        this.loading.set(false);
        const triggered = events.find((e) => e.event === 'key.decoy_used');
        const defaultId = triggered?.clientId || keys.find((k) => k.decoy)?.id;
        if (defaultId) this.selectDecoy(defaultId);
      },
      error: () => {
        this.error.set(this.i18n.t('mcp.events.error'));
        this.loading.set(false);
      },
    });
  }

  pollEvents(): void {
    forkJoin({ events: this.api.getEvents(), expiring: this.api.getExpiring() }).subscribe({
      next: ({ events, expiring }) => {
        const newIds = new Set<number>();
        for (const e of events) {
          if (!this.knownEventIds.has(e.id)) {
            newIds.add(e.id);
            this.knownEventIds.add(e.id);
          }
        }
        if (newIds.size > 0) {
          this.highlightedIds.set(newIds);
          setTimeout(() => this.highlightedIds.set(new Set()), 1000);
        }
        this.events.set(events);
        this.expiringKeys.set(expiring);
      },
    });
  }

  selectDecoy(decoyId: string): void {
    this.selectedDecoyId.set(decoyId);
    this.api.getDecoyGroup(decoyId).subscribe({
      next: (g) => this.decoyGroupMembers.set(g.members || []),
      error: () => this.decoyGroupMembers.set([]),
    });
  }

  onSelectDecoyGroup(decoyId: string): void {
    this.selectDecoy(decoyId);
    this.highlightDecoyGroup.set(true);
    setTimeout(() => this.highlightDecoyGroup.set(false), 1000);
    const reduced = typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const el = document.getElementById('decoy-group-card');
    if (el) {
      el.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
    }
  }

  removeDecoyMember(memberId: string): void {
    const decoyId = this.selectedDecoyId();
    if (!decoyId || !this.canChange()) return;
    const next = this.decoyGroupMembers().filter((id) => id !== memberId);
    this.api.updateDecoyGroup(decoyId, next).subscribe((g) => this.decoyGroupMembers.set(g.members));
  }

  addDecoyMember(memberId: string): void {
    const decoyId = this.selectedDecoyId();
    if (!decoyId || !this.canChange()) return;
    const next = [...this.decoyGroupMembers(), memberId];
    this.submitting.set(true);
    this.api.updateDecoyGroup(decoyId, next).subscribe({
      next: (g) => {
        this.decoyGroupMembers.set(g.members);
        this.submitting.set(false);
        this.showAddMemberDialog.set(false);
      },
      error: () => this.submitting.set(false),
    });
  }

  openResume(item: { id?: string | number; clientId?: string; owner?: string }): void {
    const id = item.clientId || (typeof item.id === 'string' ? item.id : undefined);
    if (id && this.canChange()) this.resumingKey.set({ id, owner: item.owner || id });
  }

  submitResume(req: ResumeRequest): void {
    const target = this.resumingKey();
    if (!target) return;
    this.submitting.set(true);
    this.api.resume(target.id, req).subscribe({
      next: () => {
        this.submitting.set(false);
        this.resumingKey.set(null);
        this.pollEvents();
        this.api.getAllKeys().subscribe((keys) => this.allKeys.set(keys));
      },
      error: () => this.submitting.set(false),
    });
  }
}
