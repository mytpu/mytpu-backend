import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { SessionStore } from '../../../../core/session/session.store';
import { BadgeComponent } from '../../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { DialogComponent } from '../../../../ui/dialog/dialog.component';
import { EmptyStateComponent } from '../../../../ui/empty-state/empty-state.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import { SkeletonComponent } from '../../../../ui/skeleton/skeleton.component';
import { ConnectorsApi } from '../connectors.api';
import { ConnectorDetails, ConnectorVersion } from '../connectors.model';
import { VersionListComponent } from './version-list.component';
import { VersionDetailsComponent } from './version-details.component';
import { ConnectorTestPanelComponent } from '../test-panel/connector-test-panel.component';
import { PublishDialogComponent } from '../publish-dialog/publish-dialog.component';
import { PublishCountdownToastComponent } from '../publish-dialog/publish-countdown-toast.component';
import { ConnectorSecretsComponent } from '../secrets/connector-secrets.component';

@Component({
  selector: 'app-connector-versions-page',
  standalone: true,
  imports: [
    TPipe,
    BadgeComponent,
    ButtonComponent,
    DialogComponent,
    EmptyStateComponent,
    IconComponent,
    SkeletonComponent,
    VersionListComponent,
    VersionDetailsComponent,
    ConnectorTestPanelComponent,
    PublishDialogComponent,
    PublishCountdownToastComponent,
    ConnectorSecretsComponent,
  ],
  templateUrl: './connector-versions.page.html',
  styleUrl: './connector-versions.page.css',
})
export class ConnectorVersionsPage implements OnInit {
  private readonly api = inject(ConnectorsApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly i18n = inject(I18n);
  private readonly liveAnnouncer = inject(LiveAnnouncer);
  readonly sessionStore = inject(SessionStore);

  readonly details = signal<ConnectorDetails | null>(null);
  readonly selectedVersion = signal<ConnectorVersion | null>(null);
  readonly loading = signal<boolean>(true);
  readonly error = signal<string | null>(null);
  readonly submittingAction = signal<boolean>(false);
  readonly savedMessage = signal<string | null>(null);

  readonly versionToPublish = signal<ConnectorVersion | null>(null);
  readonly publishedToastVersion = signal<number | null>(null);
  readonly showUnpublishDialog = signal<boolean>(false);

  readonly canChange = computed(() => this.sessionStore.canChange());

  readonly connectorTitle = computed(() => {
    const d = this.details();
    if (!d || d.versions.length === 0) return d?.id || '';
    return d.versions[0].spec?.title || d.id;
  });

  readonly connectorScope = computed(() => {
    const d = this.details();
    if (!d || d.versions.length === 0) return null;
    return d.versions[0].spec?.scope || null;
  });

  readonly publishedVersionObj = computed<ConnectorVersion | null>(() => {
    const d = this.details();
    if (!d || d.publishedVersion === null || d.publishedVersion === undefined) return null;
    return d.versions.find((v) => v.version === d.publishedVersion) || null;
  });

  readonly latestVersionObj = computed<ConnectorVersion | null>(() => {
    const d = this.details();
    return d && d.versions.length > 0 ? d.versions[0] : null;
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.error.set(this.i18n.t('connectors.notFound'));
      this.loading.set(false);
      return;
    }

    const savedVer = history.state?.savedVersion || this.route.snapshot.queryParamMap.get('saved');
    if (savedVer) {
      const msg = this.i18n.t('connectors.versionSavedDraft', { version: savedVer });
      this.savedMessage.set(msg);
      this.liveAnnouncer.announce(msg);
    }

    this.loadDetails(id);
  }

  loadDetails(id: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.details(id).subscribe({
      next: (res) => {
        this.details.set(res);
        this.loading.set(false);
        if (res.versions.length > 0) {
          const published = res.versions.find((v) => v.published || v.version === res.publishedVersion);
          this.selectedVersion.set(published || res.versions[0]);
        }
      },
      error: (err) => {
        this.error.set(err.message || this.i18n.t('connectors.errorDetails'));
        this.loading.set(false);
      },
    });
  }

  selectVersion(v: ConnectorVersion): void {
    this.selectedVersion.set(v);
  }

  goBack(): void {
    this.router.navigate(['/mcp/connectors']);
  }

  createNewVersion(): void {
    const d = this.details();
    if (!d) return;
    this.router.navigate(['/mcp/connectors', d.id, 'edit']);
  }

  promptPublish(v: ConnectorVersion): void {
    if (!this.canChange()) return;
    this.versionToPublish.set(v);
  }

  cancelPublish(): void {
    this.versionToPublish.set(null);
  }

  onPublishConfirmed(v: ConnectorVersion): void {
    this.versionToPublish.set(null);
    this.publishedToastVersion.set(v.version);
    const d = this.details();
    if (d) this.loadDetails(d.id);
  }

  promptUnpublish(): void {
    if (!this.canChange()) return;
    this.showUnpublishDialog.set(true);
  }

  cancelUnpublish(): void {
    this.showUnpublishDialog.set(false);
  }

  confirmUnpublish(): void {
    const d = this.details();
    if (!d || this.submittingAction()) return;
    this.submittingAction.set(true);
    this.api.unpublish(d.id).subscribe({
      next: () => {
        this.submittingAction.set(false);
        this.showUnpublishDialog.set(false);
        this.loadDetails(d.id);
      },
      error: (err) => {
        this.submittingAction.set(false);
        this.error.set(err.message || this.i18n.t('connectors.errorDetails'));
      },
    });
  }
}
