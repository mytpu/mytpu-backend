import { Component, computed, inject } from '@angular/core';
import {
  ActivatedRoute,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { TPipe } from '../../core/i18n/t.pipe';
import { SessionStore } from '../../core/session/session.store';
import { IdleService } from '../../core/session/idle.service';
import { ThemeService } from '../../core/theme/theme.service';
import { LogoComponent } from '../../ui/logo/logo.component';
import { IconComponent, IconName } from '../../ui/icon/icon.component';
import { ButtonComponent } from '../../ui/button/button.component';
import { DialogComponent } from '../../ui/dialog/dialog.component';

interface NavItem {
  icon: IconName;
  labelKey: string;
  path: string;
}

interface NavGroup {
  groupKey: string;
  items: NavItem[];
}

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TPipe,
    LogoComponent,
    IconComponent,
    ButtonComponent,
    DialogComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.css',
})
export class ShellComponent {
  readonly sessionStore = inject(SessionStore);
  readonly idleService = inject(IdleService);
  readonly themeService = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly activatedRoute = inject(ActivatedRoute);

  readonly navGroups: NavGroup[] = [
    {
      groupKey: 'nav.groupMcp',
      items: [
        { icon: 'home', labelKey: 'nav.overview', path: '/mcp/overview' },
        { icon: 'key', labelKey: 'nav.keys', path: '/mcp/keys' },
        { icon: 'mail', labelKey: 'nav.keyRequests', path: '/mcp/key-requests' },
        { icon: 'bell', labelKey: 'nav.keyEvents', path: '/mcp/key-events' },
        { icon: 'plug', labelKey: 'nav.integrations', path: '/mcp/integrations' },
        { icon: 'chart', labelKey: 'nav.connectors', path: '/mcp/connectors' },
        { icon: 'shield', labelKey: 'nav.mode', path: '/mcp/mode' },
        { icon: 'list', labelKey: 'nav.audit', path: '/mcp/audit' },
        { icon: 'userCheck', labelKey: 'nav.adminAudit', path: '/mcp/admin-audit' },
      ],
    },
    {
      groupKey: 'nav.groupRag',
      items: [
        { icon: 'db', labelKey: 'nav.ragOverview', path: '/rag/overview' },
        { icon: 'upload', labelKey: 'nav.ragSources', path: '/rag/sources' },
        { icon: 'file', labelKey: 'nav.ragDocuments', path: '/rag/documents' },
        { icon: 'search', labelKey: 'nav.ragPlayground', path: '/rag/playground' },
        { icon: 'target', labelKey: 'nav.ragEval', path: '/rag/eval' },
        { icon: 'help', labelKey: 'nav.ragMisses', path: '/rag/misses' },
      ],
    },
  ];

  readonly currentTitle = computed(() => {
    let r = this.activatedRoute;
    while (r.firstChild) r = r.firstChild;
    return r.snapshot.data['title'] ?? 'nav.overview';
  });

  readonly currentMeta = computed(() => {
    let r = this.activatedRoute;
    while (r.firstChild) r = r.firstChild;
    return r.snapshot.data['meta'] ?? 'header.metaDefault';
  });

  readonly initials = computed(() => {
    const owner = this.sessionStore.owner();
    if (!owner) return 'АД';
    const words = owner.trim().split(/\s+/);
    if (words.length >= 2) {
      return (words[0][0] + words[1][0]).toUpperCase();
    }
    return owner.substring(0, 2).toUpperCase();
  });

  readonly roleCode = computed(() => {
    return this.sessionStore.canChange() ? 'console.admin' : 'console.viewer';
  });
}
