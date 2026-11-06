import { Component, computed, input, output } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SelectComponent, SelectOption } from '../../../ui/select/select.component';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { CardComponent } from '../../../ui/card/card.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { KeyView } from '../keys/keys.model';
import { ExpiringKeyView } from './key-events.model';

export interface DecoyMemberInfo {
  id: string;
  owner: string;
  keyPrefix: string;
}

@Component({
  selector: 'app-key-events-sidebar',
  standalone: true,
  imports: [TPipe, SelectComponent, BadgeComponent, ButtonComponent, CardComponent, IconComponent],
  templateUrl: './key-events-sidebar.component.html',
  styleUrl: './key-events-sidebar.component.css',
})
export class KeyEventsSidebarComponent {
  readonly expiringKeys = input<ExpiringKeyView[]>([]);
  readonly decoyKeys = input<KeyView[]>([]);
  readonly selectedDecoyId = input<string | null>(null);
  readonly groupMemberIds = input<string[]>([]);
  readonly allKeys = input<KeyView[]>([]);
  readonly canChange = input.required<boolean>();
  readonly highlightDecoyGroup = input<boolean>(false);

  readonly selectDecoy = output<string>();
  readonly openAddMember = output<void>();
  readonly removeMember = output<string>();
  readonly resumeKey = output<ExpiringKeyView>();

  readonly decoyOptions = computed<SelectOption[]>(() =>
    this.decoyKeys().map((d) => ({ value: d.id, label: d.owner, hint: d.keyPrefix })),
  );

  readonly groupMembers = computed<DecoyMemberInfo[]>(() => {
    const ids = this.groupMemberIds();
    const map = new Map<string, KeyView>();
    for (const k of this.allKeys()) map.set(k.id, k);
    return ids.map((id) => {
      const found = map.get(id);
      return {
        id,
        owner: found?.owner || id,
        keyPrefix: found?.keyPrefix || '…',
      };
    });
  });

  readonly selectedDecoy = computed(() => {
    const id = this.selectedDecoyId();
    if (!id) return this.decoyKeys()[0] ?? null;
    return this.decoyKeys().find((d) => d.id === id) ?? this.decoyKeys()[0] ?? null;
  });

  daysUntil(iso: string): number {
    return Math.max(1, Math.ceil((new Date(iso).getTime() - Date.now()) / 86400000));
  }
}
