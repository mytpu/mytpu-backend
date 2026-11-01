import { Component, input, output, signal } from '@angular/core';
import { IntegrationView, ToolView } from './integrations.model';
import { IntegrationRowComponent } from './integration-row.component';
import { ToolRowComponent } from './tool-row.component';

@Component({
  selector: 'app-integrations-tree',
  standalone: true,
  imports: [IntegrationRowComponent, ToolRowComponent],
  template: `
    <div class="tree-root">
      @for (item of items(); track item.integration.id) {
        <div class="integration-node">
          <app-integration-row
            [integration]="item.integration"
            [expanded]="isExpanded(item.integration.id)"
            [canChange]="canChange()"
            (toggleExpand)="toggleExpand(item.integration.id)"
            (toggle)="onIntegToggle(item, $event)"
          />

          @if (isExpanded(item.integration.id)) {
            <div class="tools-list">
              @for (tool of item.tools; track tool.name) {
                <app-tool-row
                  [tool]="tool"
                  [selected]="selectedTool()?.name === tool.name"
                  [parentEnabled]="item.integration.enabled"
                  [canChange]="canChange()"
                  (select)="toolSelect.emit({ tool, integration: item.integration })"
                  (toggle)="onToolToggle(tool, $event)"
                />
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
    .tree-root {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
    }
    .integration-node {
      border-bottom: 1px solid var(--line);
      padding-bottom: var(--space-2);
    }
    .integration-node:last-child {
      border-bottom: none;
      padding-bottom: 0;
    }
    .tools-list {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-top: 4px;
      margin-left: 48px;
    }
  `,
})
export class IntegrationsTreeComponent {
  readonly items = input.required<IntegrationView[]>();
  readonly selectedTool = input<ToolView | null>(null);
  readonly canChange = input<boolean>(true);

  readonly toolSelect = output<{ tool: ToolView; integration: IntegrationView['integration'] }>();
  readonly integToggle = output<{ integration: IntegrationView['integration']; enabled: boolean }>();
  readonly toolToggle = output<{ tool: ToolView; enabled: boolean }>();

  readonly expandedIds = signal<Set<string>>(new Set(['lk', 'rasp', 'lms', 'help', 'devdocs', 'site', 'mail']));

  isExpanded(id: string): boolean {
    return this.expandedIds().has(id);
  }

  toggleExpand(id: string): void {
    const s = new Set(this.expandedIds());
    if (s.has(id)) s.delete(id);
    else s.add(id);
    this.expandedIds.set(s);
  }

  onIntegToggle(item: IntegrationView, enabled: boolean): void {
    this.integToggle.emit({ integration: item.integration, enabled });
  }

  onToolToggle(tool: ToolView, enabled: boolean): void {
    this.toolToggle.emit({ tool, enabled });
  }
}
