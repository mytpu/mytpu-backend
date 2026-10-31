import { Component, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';
import { KeyView } from '../keys/keys.model';

@Component({
  selector: 'app-add-decoy-member-dialog',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, DialogComponent],
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
              <select
                class="key-select"
                [ngModel]="selectedKeyId()"
                (ngModelChange)="selectedKeyId.set($event)"
              >
                @for (k of availableKeys(); track k.id) {
                  <option [value]="k.id">
                    {{ k.owner }} ({{ k.keyPrefix }}) — {{ k.kind }}
                  </option>
                }
              </select>
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
    .key-select {
      height: var(--height-field);
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-button);
      color: var(--text-primary);
      font-family: var(--font-sans);
      font-size: 13px;
      padding: 0 var(--space-3);
      outline: none;
      box-sizing: border-box;
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
