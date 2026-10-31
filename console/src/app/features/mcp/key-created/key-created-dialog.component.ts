import {
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { Clipboard } from '@angular/cdk/clipboard';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { DialogComponent } from '../../../ui/dialog/dialog.component';

@Component({
  selector: 'app-key-created-dialog',
  standalone: true,
  imports: [TPipe, ButtonComponent, IconComponent, DialogComponent],
  templateUrl: './key-created-dialog.component.html',
  styleUrl: './key-created-dialog.component.css',
})
export class KeyCreatedDialogComponent {
  private readonly clipboard = inject(Clipboard);

  readonly apiKey = input.required<string>();
  readonly command = input.required<string>();
  readonly owner = input<string>('');
  readonly scopes = input<string[]>([]);
  readonly expires = input<string>('');
  readonly isReissue = input<boolean>(false);

  readonly done = output<void>();

  readonly copiedKey = signal<boolean>(false);
  readonly copiedCommand = signal<boolean>(false);

  readonly subtitleText = computed(() => {
    const o = this.owner();
    const sc = this.scopes().join(', ');
    const exp = this.expires();
    return [o, sc, exp ? `до ${exp}` : ''].filter(Boolean).join(' · ');
  });

  copyKey(): void {
    const key = this.apiKey();
    if (!key) return;
    this.clipboard.copy(key);
    this.copiedKey.set(true);
    setTimeout(() => this.copiedKey.set(false), 2000);
  }

  copyCommand(): void {
    const cmd = this.command();
    if (!cmd) return;
    this.clipboard.copy(cmd);
    this.copiedCommand.set(true);
    setTimeout(() => this.copiedCommand.set(false), 2000);
  }

  onSave(): void {
    this.done.emit();
  }
}
