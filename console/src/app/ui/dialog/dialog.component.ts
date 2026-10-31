import {
  Component,
  HostListener,
  Optional,
  inject,
  input,
  output,
} from '@angular/core';
import { DialogRef, DIALOG_DATA } from '@angular/cdk/dialog';
import { IconComponent } from '../icon/icon.component';

export interface DialogData {
  title?: string;
  subtitle?: string;
  locked?: boolean;
}

@Component({
  selector: 'app-dialog',
  standalone: true,
  imports: [IconComponent],
  template: `
    <div class="dialog-box" role="dialog" aria-modal="true" [attr.aria-label]="title()">
      <div class="dialog-head">
        <div class="dialog-titles">
          @if (title()) {
            <h2 class="dialog-title">{{ title() }}</h2>
          }
          @if (subtitle()) {
            <p class="dialog-subtitle">{{ subtitle() }}</p>
          }
        </div>
        @if (!locked()) {
          <button
            type="button"
            class="close-btn"
            (click)="close()"
            aria-label="Закрыть"
          >
            <app-icon name="x" [size]="18" />
          </button>
        }
      </div>

      <div class="dialog-body">
        <ng-content />
      </div>

      <div class="dialog-foot">
        <ng-content select="[actions]" />
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
      max-width: 520px;
    }
    .dialog-box {
      background: var(--bg-tile);
      border: 1px solid var(--border-tile);
      border-radius: var(--radius-card);
      padding: var(--space-6);
      box-shadow: var(--shadow-dialog);
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
      box-sizing: border-box;
      outline: none;
    }
    .dialog-head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--space-3);
    }
    .dialog-titles {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .dialog-title {
      margin: 0;
      font-size: 20px;
      font-weight: 800;
      color: var(--text-primary);
      line-height: 1.2;
    }
    .dialog-subtitle {
      margin: 0;
      font-size: 14px;
      color: var(--text-secondary);
      line-height: 1.4;
    }
    .close-btn {
      background: transparent;
      border: none;
      padding: 4px;
      border-radius: var(--radius-button);
      color: var(--text-muted);
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    }
    .close-btn:hover {
      background: var(--bg-nested);
      color: var(--text-primary);
    }
    .dialog-body {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
    }
    .dialog-foot {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: var(--space-2);
      empty-cells: hide;
    }
    .dialog-foot:empty {
      display: none;
    }
  `,
})
export class DialogComponent {
  private readonly dialogRef = inject(DialogRef, { optional: true });
  private readonly injectedData = inject<DialogData>(DIALOG_DATA, { optional: true });

  readonly title = input<string | null>(this.injectedData?.title ?? null);
  readonly subtitle = input<string | null>(this.injectedData?.subtitle ?? null);
  readonly locked = input<boolean>(this.injectedData?.locked ?? false);

  readonly closed = output<void>();

  @HostListener('document:keydown.escape')
  handleEscape(): void {
    if (!this.locked()) {
      this.close();
    }
  }

  close(): void {
    this.closed.emit();
    this.dialogRef?.close();
  }
}
