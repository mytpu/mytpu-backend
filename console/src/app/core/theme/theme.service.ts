import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'mytpu.console.theme';

@Injectable({
  providedIn: 'root',
})
export class ThemeService {
  readonly theme = signal<Theme>(this.getInitialTheme());

  constructor() {
    this.applyTheme(this.theme());
  }

  toggleTheme(): void {
    const next: Theme = this.theme() === 'light' ? 'dark' : 'light';
    this.setTheme(next);
  }

  setTheme(theme: Theme): void {
    this.theme.set(theme);
    this.applyAnimated(theme);
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem(STORAGE_KEY, theme);
      }
    } catch {
      // localStorage may fail in private/sandbox mode
    }
  }

  /**
   * Смена темы с плавным переходом кадра. Без поддержки View Transitions, при «меньше движения» и в фоновой вкладке
   * (там переход обрывается InvalidStateError) — сразу, без анимации.
   */
  private applyAnimated(theme: Theme): void {
    const doc = typeof document !== 'undefined' ? (document as Document & { startViewTransition?: (cb: () => void) => unknown }) : null;
    const reduced = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!doc?.startViewTransition || reduced || doc.visibilityState !== 'visible') {
      this.applyTheme(theme);
      return;
    }
    try {
      doc.startViewTransition(() => this.applyTheme(theme));
    } catch {
      this.applyTheme(theme);
    }
  }

  private applyTheme(theme: Theme): void {
    if (typeof document !== 'undefined') {
      document.documentElement.dataset['theme'] = theme;
    }
  }

  private getInitialTheme(): Theme {
    try {
      if (typeof localStorage !== 'undefined') {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (stored === 'light' || stored === 'dark') {
          return stored;
        }
      }
    } catch {
      // Ignore localStorage read errors
    }

    // По умолчанию — светлая, а не как в системе (решение владельца 06.11, docs/DECISIONS.md)
    return 'light';
  }
}
