import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { environment } from '../../../environments/environment';
import { TPipe } from '../../core/i18n/t.pipe';
import { I18n } from '../../core/i18n/i18n.service';
import { SessionStore } from '../../core/session/session.store';
import { AdminError } from '../../core/api/api';
import { LogoComponent } from '../../ui/logo/logo.component';
import { FieldComponent } from '../../ui/field/field.component';
import { ButtonComponent } from '../../ui/button/button.component';
import { IconComponent } from '../../ui/icon/icon.component';
import { ThemeToggleComponent } from '../../ui/theme-toggle/theme-toggle.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    TPipe,
    LogoComponent,
    FieldComponent,
    ButtonComponent,
    IconComponent,
    ThemeToggleComponent,
  ],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  readonly isDemo = environment.demo;
  private readonly sessionStore = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  readonly apiKey = signal<string>('');
  readonly loading = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);

  onKeyInput(val: string): void {
    this.apiKey.set(val);
    if (this.errorMessage()) {
      this.errorMessage.set(null);
    }
  }

  onSubmit(event: Event): void {
    event.preventDefault();
    const key = this.apiKey().trim();
    if (!key || this.loading()) return;

    this.loading.set(true);
    this.errorMessage.set(null);

    this.sessionStore.login(key).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigate(['/mcp/overview']);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (err instanceof AdminError) {
          switch (err.status) {
            case 401:
              this.errorMessage.set(this.i18n.t('errors.401'));
              break;
            case 403:
              this.errorMessage.set(this.i18n.t('errors.403'));
              break;
            case 404:
              this.errorMessage.set(this.i18n.t('errors.404'));
              break;
            case 429:
              this.errorMessage.set(this.i18n.t('errors.429'));
              break;
            case 0:
              this.errorMessage.set(this.i18n.t('errors.network'));
              break;
            default:
              this.errorMessage.set(this.i18n.t('errors.unknown'));
          }
        } else {
          this.errorMessage.set(this.i18n.t('errors.network'));
        }
      },
    });
  }
}
