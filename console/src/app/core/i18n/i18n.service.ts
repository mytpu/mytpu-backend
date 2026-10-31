import { Injectable } from '@angular/core';
import ruMessages from '../../../i18n/ru.json';

@Injectable({
  providedIn: 'root',
})
export class I18n {
  private readonly messages: Record<string, unknown> = ruMessages;

  t(key: string, params?: Record<string, string | number>): string {
    const segments = key.split('.');
    let current: unknown = this.messages;

    for (const segment of segments) {
      if (current && typeof current === 'object' && segment in current) {
        current = (current as Record<string, unknown>)[segment];
      } else {
        return key;
      }
    }

    if (typeof current !== 'string') {
      return key;
    }

    if (!params) {
      return current;
    }

    return current.replace(/\{\{\s*(\w+)\s*\}\}/g, (_, placeholder) => {
      const val = params[placeholder];
      return val !== undefined ? String(val) : `{{${placeholder}}}`;
    });
  }
}
