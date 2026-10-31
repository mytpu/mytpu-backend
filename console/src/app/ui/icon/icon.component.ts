import { Component, computed, input } from '@angular/core';

export type IconName =
  | 'home'
  | 'key'
  | 'mail'
  | 'bell'
  | 'plug'
  | 'chart'
  | 'shield'
  | 'list'
  | 'userCheck'
  | 'db'
  | 'upload'
  | 'file'
  | 'search'
  | 'target'
  | 'help'
  | 'logout'
  | 'moon'
  | 'sun'
  | 'plus'
  | 'lock'
  | 'alert'
  | 'check'
  | 'x'
  | 'clock'
  | 'filter'
  | 'refresh'
  | 'eye'
  | 'user'
  | 'copy'
  | 'chevDown';

const ICONS: Record<IconName, string> = {
  home: 'M4 10.2 L12 3.5 L20 10.2 L20 19 A2 2 0 0 1 18 21 L6 21 A2 2 0 0 1 4 19 Z M10 20 L10 15.5 A1 1 0 0 1 11 14.5 L13 14.5 A1 1 0 0 1 14 15.5 L14 20',
  key: 'M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M10.8 12.2 20 3M16 7l3 3M14 9l2 2',
  mail: 'M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z M3 7l9 6 9-6',
  bell: 'M6 16v-5a6 6 0 0 1 12 0v5l2 2H4zM10 21h4',
  plug: 'M9 2v5M15 2v5M6 7h12v4a6 6 0 0 1-12 0zM12 17v5',
  chart: 'M4 20V10M10 20V4M16 20v-7M2 20h20',
  shield: 'M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z',
  list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  userCheck: 'M6 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M3 21c0-4 3-6 7-6s7 2 7 6M16 11l2 2 4-4',
  db: 'M4 5c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  upload: 'M12 16V4M6 10l6-6 6 6M4 20h16',
  file: 'M6 2h8l5 5v15H6zM14 2v5h5M9 13h7M9 17h4',
  search: 'M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0 M20 20l-4-4',
  target: 'M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M7 12a5 5 0 1 0 10 0a5 5 0 1 0 -10 0 M12 12h.01',
  help: 'M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17.5v.01',
  logout: 'M15 4h4v16h-4M10 8l-4 4 4 4M6 12h11',
  moon: 'M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z',
  sun: 'M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  plus: 'M12 5v14M5 12h14',
  lock: 'M6 11h12a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z M8 11V7a4 4 0 0 1 8 0v4',
  alert: 'M12 3l10 18H2zM12 10v4M12 17.5v.01',
  check: 'M5 12l5 5 9-10',
  x: 'M6 6l12 12M18 6L6 18',
  clock: 'M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 7v5l3 2',
  filter: 'M3 5h18l-7 8v6l-4 2v-8z',
  refresh: 'M20 11a8 8 0 0 0-14.5-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.5 4.5L20 16M20 20v-4h-4',
  eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0',
  user: 'M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M4 21c0-4 3.6-6 8-6s8 2 8 6',
  copy: 'M10 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2z M4 16V6a2 2 0 0 1 2-2h10',
  chevDown: 'M6 9l6 6 6-6',
};

@Component({
  selector: 'app-icon',
  standalone: true,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      [attr.stroke-width]="strokeWidth()"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path [attr.d]="d()" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      line-height: 0;
      color: inherit;
    }
  `,
})
export class IconComponent {
  readonly name = input.required<IconName>();
  readonly size = input<number>(20);
  readonly strokeWidth = input<number>(2);

  readonly d = computed(() => ICONS[this.name()] ?? '');
}
