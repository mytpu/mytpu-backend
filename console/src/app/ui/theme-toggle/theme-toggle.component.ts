import { Component, inject } from '@angular/core';
import { ThemeService } from '../../core/theme/theme.service';
import { TPipe } from '../../core/i18n/t.pipe';
import { IconComponent } from '../icon/icon.component';

/** Кнопка смены светлой и тёмной темы: в шапке консоли и на экране входа. */
@Component({
  selector: 'app-theme-toggle',
  standalone: true,
  imports: [TPipe, IconComponent],
  template: `
    <button
      type="button"
      class="theme-btn"
      (click)="themeService.toggleTheme()"
      [attr.title]="'header.toggleTheme' | t"
      [attr.aria-label]="'header.toggleTheme' | t"
    >
      <app-icon [name]="themeService.theme() === 'dark' ? 'sun' : 'moon'" [size]="20" />
    </button>
  `,
  styles: `
    .theme-btn {
      width: 40px;
      height: 40px;
      border-radius: var(--radius-button);
      background: var(--bg-tile);
      border: 1px solid var(--line);
      color: var(--text-primary);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
    }
    .theme-btn:hover {
      background: var(--bg-nested);
    }
  `,
})
export class ThemeToggleComponent {
  readonly themeService = inject(ThemeService);
}
