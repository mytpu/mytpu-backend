import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import { ConnectorsApi } from '../connectors.api';
import {
  ConnectorParam,
  ConnectorTestResult,
  ConnectorToolSpec,
  ConnectorVersion,
} from '../connectors.model';

@Component({
  selector: 'app-connector-test-panel',
  standalone: true,
  imports: [FormsModule, TPipe, BadgeComponent, ButtonComponent, IconComponent],
  templateUrl: './connector-test-panel.component.html',
  styleUrl: './connector-test-panel.component.css',
})
export class ConnectorTestPanelComponent {
  private readonly api = inject(ConnectorsApi);

  defaultText(value: unknown): string {
    return value === undefined || value === null ? '' : String(value);
  }

  readonly connectorId = input.required<string>();
  readonly version = input.required<ConnectorVersion>();
  readonly canChange = input<boolean>(true);

  readonly tools = computed(() => this.version().spec?.tools || []);
  readonly selectedToolName = linkedSignal(() => this.tools()[0]?.name || '');

  readonly currentTool = computed<ConnectorToolSpec | null>(() => {
    const list = this.tools();
    return list.find((t) => t.name === this.selectedToolName()) || list[0] || null;
  });

  readonly argValues = linkedSignal<Record<string, any>>(() => {
    const tool = this.currentTool();
    const map: Record<string, any> = {};
    if (tool?.params) {
      for (const p of tool.params) {
        if (p.default !== undefined && p.default !== null) {
          map[p.name] = p.default;
        } else if (p.enum && p.enum.length > 0) {
          map[p.name] = p.enum[0];
        } else {
          map[p.name] = '';
        }
      }
    }
    return map;
  });

  readonly testing = signal<boolean>(false);
  readonly testResult = signal<ConnectorTestResult | null>(null);
  readonly rawExpanded = signal<boolean>(false);

  readonly arrayData = computed<{ columns: string[]; rows: Record<string, string>[] } | null>(() => {
    const d = this.testResult()?.data;
    if (!Array.isArray(d) || d.length === 0) return null;
    if (typeof d[0] === 'object' && d[0] !== null) {
      const keys = Array.from(new Set(d.flatMap((item) => Object.keys(item))));
      const rows = d.map((item) => {
        const row: Record<string, string> = {};
        for (const k of keys) {
          const val = item[k];
          row[k] = val !== undefined && val !== null ? (typeof val === 'object' ? JSON.stringify(val) : String(val)) : '—';
        }
        return row;
      });
      return { columns: keys, rows };
    }
    return { columns: ['value'], rows: d.map((v) => ({ value: String(v) })) };
  });

  readonly objectData = computed<{ key: string; val: string }[] | null>(() => {
    const d = this.testResult()?.data;
    if (!d || Array.isArray(d) || typeof d !== 'object') return null;
    return Object.entries(d).map(([k, v]) => ({
      key: k,
      val: v !== undefined && v !== null ? (typeof v === 'object' ? JSON.stringify(v) : String(v)) : '—',
    }));
  });

  onToolChange(name: string): void {
    this.selectedToolName.set(name);
    this.testResult.set(null);
  }

  setArg(name: string, val: any): void {
    const cur = { ...this.argValues() };
    cur[name] = val;
    this.argValues.set(cur);
  }

  runTest(): void {
    const tool = this.currentTool();
    if (!tool || !this.canChange() || this.testing()) return;

    const rawArgs = this.argValues();
    const argsToSend: Record<string, unknown> = {};

    for (const p of tool.params || []) {
      const v = rawArgs[p.name];
      if (v !== undefined && v !== null && v !== '') {
        if (p.type === 'integer') {
          argsToSend[p.name] = parseInt(v, 10);
        } else if (p.type === 'number') {
          argsToSend[p.name] = Number(v);
        } else if (p.type === 'boolean') {
          argsToSend[p.name] = v === true || v === 'true';
        } else {
          argsToSend[p.name] = v;
        }
      }
    }

    this.testing.set(true);
    this.testResult.set(null);

    this.api.test(this.connectorId(), this.version().version, { tool: tool.name, arguments: argsToSend }).subscribe({
      next: (res) => {
        this.testResult.set(res);
        this.testing.set(false);
      },
      error: (err) => {
        this.testResult.set({
          ok: false,
          error: err.code || 'test_failed',
          message: err.message,
          problems: err.raw?.problems,
        });
        this.testing.set(false);
      },
    });
  }
}
