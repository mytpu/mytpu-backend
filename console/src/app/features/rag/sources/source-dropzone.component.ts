import { Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { TPipe } from '../../../core/i18n/t.pipe';
import { I18n } from '../../../core/i18n/i18n.service';
import { ButtonComponent } from '../../../ui/button/button.component';
import { IconComponent } from '../../../ui/icon/icon.component';
import { SourcesApi } from './sources.api';

const MAX_BYTES = 20 * 1024 * 1024;
const ALLOWED_EXTS = ['.pdf', '.docx', '.html', '.htm', '.md', '.txt'];

@Component({
  selector: 'app-source-dropzone',
  standalone: true,
  imports: [FormsModule, TPipe, ButtonComponent, IconComponent],
  templateUrl: './source-dropzone.component.html',
  styleUrl: './source-dropzone.component.css',
})
export class SourceDropzoneComponent {
  private readonly api = inject(SourcesApi);
  private readonly i18n = inject(I18n);

  readonly disabled = input<boolean>(false);
  readonly created = output<{ sourceId: number; jobId: number }>();

  readonly isDragOver = signal<boolean>(false);
  readonly fileError = signal<string | null>(null);
  readonly rejectedName = signal<string>('');
  readonly rejectedSize = signal<string>('');
  readonly uploadProgress = signal<number | null>(null);

  readonly pageUrl = signal<string>('');
  readonly pageError = signal<string | null>(null);
  readonly pageSubmitting = signal<boolean>(false);

  onZoneClick(inputEl: HTMLInputElement): void {
    if (this.disabled() || this.uploadProgress() !== null) return;
    inputEl.click();
  }

  onDragOver(e: DragEvent): void {
    e.preventDefault();
    if (!this.disabled()) this.isDragOver.set(true);
  }

  onDragLeave(e: DragEvent): void {
    e.preventDefault();
    this.isDragOver.set(false);
  }

  onDrop(e: DragEvent): void {
    e.preventDefault();
    this.isDragOver.set(false);
    if (this.disabled()) return;
    const file = e.dataTransfer?.files?.[0];
    if (file) this.processFile(file);
  }

  onFileSelected(e: Event): void {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.processFile(file);
    input.value = '';
  }

  private processFile(file: File): void {
    const ext = '.' + file.name.split('.').pop()?.toLowerCase();
    if (!ALLOWED_EXTS.includes(ext)) {
      this.rejectedName.set(file.name);
      this.rejectedSize.set(this.i18n.t('rag.sources.fileSizeMb', { mb: (file.size / 1024 / 1024).toFixed(1) }));
      this.fileError.set(this.i18n.t('rag.sources.dropErrorFormat'));
      return;
    }
    if (file.size > MAX_BYTES) {
      this.rejectedName.set(file.name);
      this.rejectedSize.set(this.i18n.t('rag.sources.fileSizeMb', { mb: Math.round(file.size / 1024 / 1024) }));
      this.fileError.set(this.i18n.t('rag.sources.dropErrorSize'));
      return;
    }
    this.uploadProgress.set(0);
    this.api.uploadFile(file).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.uploadProgress.set(Math.round((event.loaded / event.total) * 100));
        } else if (event.type === HttpEventType.Response && event.body) {
          this.uploadProgress.set(null);
          this.created.emit(event.body);
        }
      },
      error: () => {
        this.uploadProgress.set(null);
        this.fileError.set(this.i18n.t('rag.sources.errorUpload'));
      },
    });
  }

  resetError(): void {
    this.fileError.set(null);
  }

  onUrlChange(val: string): void {
    this.pageUrl.set(val);
    this.pageError.set(null);
  }

  submitPage(): void {
    const url = this.pageUrl().trim();
    try {
      const u = new URL(url);
      if (u.protocol !== 'https:' || !(u.hostname === 'tpu.ru' || u.hostname.endsWith('.tpu.ru'))) {
        this.pageError.set(this.i18n.t('rag.sources.pageUrlError'));
        return;
      }
    } catch {
      this.pageError.set(this.i18n.t('rag.sources.invalidUrl'));
      return;
    }
    this.pageSubmitting.set(true);
    this.api.addPage(url).subscribe({
      next: (res) => {
        this.pageSubmitting.set(false);
        this.pageUrl.set('');
        this.created.emit(res);
      },
      error: () => {
        this.pageSubmitting.set(false);
        this.pageError.set(this.i18n.t('rag.sources.errorAddPage'));
      },
    });
  }
}
