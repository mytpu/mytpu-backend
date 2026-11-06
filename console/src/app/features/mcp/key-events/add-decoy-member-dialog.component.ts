import { Component, computed, effect, input, output, signal } from '@angular/core';
import { TPipe } from '../../../core/i18n/t.pipe';
import { SelectComponent, SelectOption } from '../../../ui/select/select.component';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { KeyView } from '../keys/keys.model';

@Component({
  selector: 'app-add-decoy-member-dialog',
  standalone: true,
  imports: [TPipe, SelectComponent, ButtonComponent, DialogComponent],
  template: `
    <div class="overlay">
      <app-dialog
        [title]="'mcp.events.dialogAddKeyTitle' | t"
        [subtitle]="'mcp.events.dialogAddKeySubtitle' | t"
        (closed)="cancel.emit()"
      >
        <div class="dialog-body">
          @if (availableKeys().length === 0) {
            <p class="empty-text">{{ 'mcp.events.dialogAddKeyEmpty' | t }}</p>
          } @else {
            <div class="field-col">
              <label class="label">{{ 'mcp.events.dialogAddKeySelect' | t }}</label>
              <app-select
                block
                [options]="keyOptions()"
                [value]="selectedKeyId()"
                (valueChange)="selectedKeyId.set($event)"
              />
            </div>
          }
        </div>

        <div actions class="actions-row">
          <app-button kind="ghost" (btnClick)="cancel.emit()">
            {{ 'actions.cancel' | t }}
          </app-button>
          <app-button
            kind="primary"
            [disabled]="submitting() || !selectedKeyId()"
            (btnClick)="onSubmit()"
          >
            {{ 'mcp.events.dialogAddKeySubmit' | t }}
          </app-button>
        </div>
      </app-dialog>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .overlay {
      position: fixed;
      inset: 0;
      background: var(--bg-overlay);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 999;
      padding: var(--space-4);
      box-sizing: border-box;
    }
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .field-col {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .label {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .empty-text {
      font-size: 13px;
      color: var(--text-muted);
      margin: 0;
    }
    .actions-row {
      display: flex;
      justify-content: flex-end;
      gap: var(--space-2);
      width: 100%;
    }
  `,
})
export class AddDecoyMemberDialogComponent {
  readonly availableKeys = input<KeyView[]>([]);
  readonly submitting = input<boolean>(false);

  readonly selectedKeyId = signal<string>('');
  readonly keyOptions = computed<SelectOption[]>(() =>
    this.availableKeys().map((k) => ({ value: k.id, label: k.owner, hint: `${k.keyPrefix} · ${k.kind}` })),
  );

  readonly addMember = output<string>();
  readonly cancel = output<void>();

  constructor() {
    effect(() => {
      const list = this.availableKeys();
      if (list.length > 0 && !this.selectedKeyId()) {
        this.selectedKeyId.set(list[0].id);
      }
    });
  }

  onSubmit(): void {
    const id = this.selectedKeyId();
    if (id) {
      this.addMember.emit(id);
    }
  }
}
