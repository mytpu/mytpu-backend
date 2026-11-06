import { Component, computed, inject, input, output, signal } from '@angular/core';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { BadgeComponent } from '../../../../ui/badge/badge.component';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { DialogComponent } from '../../../../ui/dialog/dialog.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import { ConnectorsApi } from '../connectors.api';
import {
  ConnectorToolSpec,
  ConnectorVersion,
} from '../connectors.model';

export interface ModifiedToolDiff {
  name: string;
  changes: string[];
}

@Component({
  selector: 'app-publish-dialog',
  standalone: true,
  imports: [
    CdkTrapFocus,
    TPipe,
    BadgeComponent,
    ButtonComponent,
    DialogComponent,
    IconComponent,
  ],
  templateUrl: './publish-dialog.component.html',
  styleUrl: './publish-dialog.component.css',
})
export class PublishDialogComponent {
  private readonly api = inject(ConnectorsApi);
  private readonly i18n = inject(I18n);
  private readonly liveAnnouncer = inject(LiveAnnouncer);

  readonly connectorId = input.required<string>();
  readonly targetVersion = input.required<ConnectorVersion>();
  readonly publishedVersion = input<ConnectorVersion | null>(null);
  readonly publishedVersionNumber = input<number | null>(null);

  readonly confirmed = output<ConnectorVersion>();
  readonly cancelled = output<void>();

  readonly submitting = signal<boolean>(false);
  readonly error = signal<string | null>(null);

  readonly isFirstPublish = computed(() => {
    return this.publishedVersionNumber() === null || !this.publishedVersion();
  });

  readonly isRollback = computed(() => {
    const pub = this.publishedVersionNumber();
    return pub !== null && this.targetVersion().version < pub;
  });

  readonly dialogTitle = computed(() => {
    const v = this.targetVersion().version;
    if (this.isRollback()) {
      return this.i18n.t('connectors.rollbackDialogTitle', { version: v });
    }
    return this.i18n.t('connectors.publishDialogTitle', { version: v });
  });

  readonly dialogSubtitle = computed(() => {
    const v = this.targetVersion().version;
    if (this.isFirstPublish()) {
      return this.i18n.t('connectors.publishFirstTimeSubtitle');
    }
    if (this.isRollback()) {
      return this.i18n.t('connectors.rollbackDialogSubtitle', { version: v });
    }
    return this.i18n.t('connectors.publishDialogSubtitle');
  });

  readonly baseUrlDiff = computed(() => {
    const pub = this.publishedVersion()?.spec;
    const tgt = this.targetVersion().spec;
    if (!pub || pub.baseUrl === tgt.baseUrl) return null;
    return { before: pub.baseUrl, after: tgt.baseUrl };
  });

  readonly authDiff = computed(() => {
    const pub = this.publishedVersion()?.spec;
    const tgt = this.targetVersion().spec;
    if (!pub) return null;
    const pStr = `${pub.auth.type}:${pub.auth.name || ''}`;
    const tStr = `${tgt.auth.type}:${tgt.auth.name || ''}`;
    if (pStr === tStr) return null;
    return { before: pub.auth.type, after: tgt.auth.type };
  });

  readonly scopeDiff = computed(() => {
    const pub = this.publishedVersion()?.spec;
    const tgt = this.targetVersion().spec;
    if (!pub) return null;
    if (pub.scope.name === tgt.scope.name && pub.scope.title === tgt.scope.title) return null;
    return { before: pub.scope.name, after: tgt.scope.name };
  });

  readonly toolsDiff = computed(() => {
    const pubTools = this.publishedVersion()?.spec?.tools || [];
    const tgtTools = this.targetVersion().spec?.tools || [];

    const pubMap = new Map<string, ConnectorToolSpec>(pubTools.map((t) => [t.name, t]));
    const tgtMap = new Map<string, ConnectorToolSpec>(tgtTools.map((t) => [t.name, t]));

    const added: ConnectorToolSpec[] = [];
    const removed: ConnectorToolSpec[] = [];
    const modified: ModifiedToolDiff[] = [];
    let unchanged = 0;

    for (const tgt of tgtTools) {
      const pub = pubMap.get(tgt.name);
      if (!pub) {
        added.push(tgt);
      } else {
        const changes: string[] = [];
        if (tgt.path !== pub.path) {
          changes.push(this.i18n.t('connectors.diffFieldPath'));
        }
        if (JSON.stringify(tgt.params) !== JSON.stringify(pub.params)) {
          changes.push(this.i18n.t('connectors.diffFieldParams'));
        }
        if (JSON.stringify(tgt.result) !== JSON.stringify(pub.result)) {
          changes.push(this.i18n.t('connectors.diffFieldResult'));
        }
        if (tgt.summary !== pub.summary) {
          changes.push(this.i18n.t('connectors.diffFieldSummary'));
        }

        if (changes.length > 0) {
          modified.push({ name: tgt.name, changes });
        } else {
          unchanged++;
        }
      }
    }

    for (const pub of pubTools) {
      if (!tgtMap.has(pub.name)) {
        removed.push(pub);
      }
    }

    return { added, removed, modified, unchanged };
  });

  cancel(): void {
    if (!this.submitting()) {
      this.cancelled.emit();
    }
  }

  submitPublish(): void {
    if (this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);

    const v = this.targetVersion();
    this.api.publish(this.connectorId(), v.version).subscribe({
      next: () => {
        this.submitting.set(false);
        const announceKey = this.isRollback()
          ? 'connectors.toastRollbackDone'
          : 'connectors.toastPublishDone';
        this.liveAnnouncer.announce(this.i18n.t(announceKey, { version: v.version }));
        this.confirmed.emit(v);
      },
      error: (err) => {
        this.submitting.set(false);
        this.error.set(err.message || this.i18n.t('connectors.error'));
      },
    });
  }
}
