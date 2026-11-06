import { Component, computed, inject, input, model, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SelectComponent, SelectOption } from '../../../ui/select/select.component';
import { I18n } from '../../../core/i18n/i18n.service';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { TableComponent, TableColumn } from '../../../ui/table/table.component';
import { EmptyStateComponent } from '../../../ui/empty-state/empty-state.component';
import { getEventBadge, KeyEventView } from './key-events.model';

@Component({
  selector: 'app-key-events-feed',
  standalone: true,
  imports: [
    FormsModule, TPipe, SelectComponent, BadgeComponent, ButtonComponent,
    IconComponent, TableComponent, EmptyStateComponent,
  ],
  templateUrl: './key-events-feed.component.html',
  styleUrl: './key-events-feed.component.css',
})
export class KeyEventsFeedComponent {
  private readonly i18n = inject(I18n);

  readonly events = input.required<KeyEventView[]>();
  readonly highlightedIds = input<Set<number>>(new Set());
  readonly canChange = input.required<boolean>();

  readonly eventTypeFilter = model<string>('all');
  readonly searchQuery = model<string>('');

  readonly resume = output<KeyEventView>();

  readonly columns: TableColumn[] = [
    { title: this.i18n.t('mcp.events.colWhen'), width: '100px' },
    { title: this.i18n.t('mcp.events.colEvent'), width: '180px' },
    { title: this.i18n.t('mcp.events.colKey'), width: '220px' },
    { title: this.i18n.t('mcp.events.colNetwork'), width: '140px' },
    { title: this.i18n.t('mcp.events.colDetail') },
  ];

  readonly eventTypes = [
    { value: 'all', labelKey: 'mcp.events.filterAllEvents' },
    { value: 'key.decoy_used', labelKey: 'mcp.events.labelDecoyUsed' },
    { value: 'key.group_suspended', labelKey: 'mcp.events.labelGroupSuspended' },
    { value: 'key.suspended', labelKey: 'mcp.events.labelSuspended' },
    { value: 'key.resumed', labelKey: 'mcp.events.labelResumed' },
    { value: 'key.inactive_suspended', labelKey: 'mcp.events.labelInactiveSuspended' },
    { value: 'key.expiring', labelKey: 'mcp.events.labelExpiring' },
    { value: 'key.network_burst', labelKey: 'mcp.events.labelNetworkBurst' },
    { value: 'key.self_rotated', labelKey: 'mcp.events.labelSelfRotated' },
    { value: 'key.new_network', labelKey: 'mcp.events.labelNewNetwork' },
    { value: 'key.foreign_country', labelKey: 'mcp.events.labelForeignCountry' },
    { value: 'key.geo_unknown', labelKey: 'mcp.events.labelGeoUnknown' },
  ];

  readonly eventTypeOptions: SelectOption[] = this.eventTypes.map((t) => ({
    value: t.value,
    label: this.i18n.t(t.labelKey),
  }));

  readonly filteredEvents = computed(() => {
    const ev = this.eventTypeFilter();
    const q = this.searchQuery().trim().toLowerCase();

    return this.events().filter((e) => {
      if (ev !== 'all' && e.event !== ev) return false;
      if (q) {
        const text = `${e.owner || ''} ${e.clientId} ${e.network || ''} ${e.detail || ''}`.toLowerCase();
        return text.includes(q);
      }
      return true;
    });
  });

  badgeInfo(event: string) {
    return getEventBadge(event);
  }

  badgeLabel(e: KeyEventView): string {
    if (e.event === 'key.foreign_country') {
      const match = e.detail?.match(/country=([A-Za-z]{2})/);
      const code = match ? match[1] : '';
      const base = this.i18n.t('mcp.events.labelForeignCountry');
      return code ? `${base} (${code})` : base;
    }
    const info = getEventBadge(e.event);
    return this.i18n.t(info.labelKey);
  }

  formatTime(iso: string): string {
    const d = new Date(iso);
    return `${d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })} ${d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`;
  }
}
