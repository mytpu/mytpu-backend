import { Pipe, PipeTransform, inject } from '@angular/core';
import { I18n } from './i18n.service';

@Pipe({
  name: 't',
  standalone: true,
})
export class TPipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: string, params?: Record<string, string | number>): string {
    return this.i18n.t(key, params);
  }
}
