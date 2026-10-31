import {
  Component,
  computed,
  input,
  model,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../ui/badge/badge.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { IntegrationCatalogView, ToolCatalogView } from './key-wizard.model';

@Component({
  selector: 'app-services-tree',
  standalone: true,
  imports: [FormsModule, TPipe, BadgeComponent, IconComponent],
  templateUrl: './services-tree.component.html',
  styleUrl: './services-tree.component.css',
})
export class ServicesTreeComponent {
  readonly catalog = input.required<IntegrationCatalogView[]>();
  readonly selectedScopes = model<string[]>([]);
  readonly deniedTools = model<string[]>([]);

  readonly filterMode = signal<'all' | 'selected'>('all');
  readonly searchTool = signal<string>('');
  readonly expandedSet = signal<Set<string>>(new Set<string>());

  readonly filteredIntegrations = computed(() => {
    const list = this.catalog();
    const mode = this.filterMode();
    const q = this.searchTool().trim().toLowerCase();

    return list.filter((item) => {
      const isSel = this.isServiceSelected(item);
      if (mode === 'selected' && !isSel) return false;

      if (q) {
        const matchTitle = item.integration.title.toLowerCase().includes(q);
        const matchTool = item.tools.some(
          (t) =>
            t.name.toLowerCase().includes(q) ||
            t.description.toLowerCase().includes(q),
        );
        if (!matchTitle && !matchTool) return false;
      }
      return true;
    });
  });

  isExpanded(id: string): boolean {
    return this.expandedSet().has(id);
  }

  toggleExpand(id: string): void {
    const next = new Set(this.expandedSet());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.expandedSet.set(next);
  }

  isServiceSelected(item: IntegrationCatalogView): boolean {
    const scopes = item.integration.scopes.map((s) => s.name);
    return scopes.some((s) => this.selectedScopes().includes(s));
  }

  selectedToolsCount(item: IntegrationCatalogView): number {
    return item.tools.filter((t) => this.isToolSelected(t)).length;
  }

  isToolSelected(tool: ToolCatalogView): boolean {
    const scopeSelected = this.selectedScopes().includes(tool.scope);
    const denied = this.deniedTools().includes(tool.name);
    return scopeSelected && !denied;
  }

  toggleService(item: IntegrationCatalogView, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const scopeNames = item.integration.scopes.map((s) => s.name);
    let nextScopes = [...this.selectedScopes()];

    if (checked) {
      for (const s of scopeNames) {
        if (!nextScopes.includes(s)) nextScopes.push(s);
      }
      const toolNames = item.tools.map((t) => t.name);
      this.deniedTools.set(
        this.deniedTools().filter((t) => !toolNames.includes(t)),
      );
      const nextExp = new Set(this.expandedSet());
      nextExp.add(item.integration.id);
      this.expandedSet.set(nextExp);
    } else {
      nextScopes = nextScopes.filter((s) => !scopeNames.includes(s));
    }
    this.selectedScopes.set(nextScopes);
  }

  toggleTool(tool: ToolCatalogView, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    let nextDenied = [...this.deniedTools()];

    if (checked) {
      nextDenied = nextDenied.filter((n) => n !== tool.name);
      if (!this.selectedScopes().includes(tool.scope)) {
        this.selectedScopes.set([...this.selectedScopes(), tool.scope]);
      }
    } else {
      if (!nextDenied.includes(tool.name)) {
        nextDenied.push(tool.name);
      }
    }
    this.deniedTools.set(nextDenied);
  }
}
