import { Injectable, signal } from '@angular/core';
import { environment } from '../../../environments/environment';

@Injectable({
  providedIn: 'root',
})
export class IdleService {
  readonly warning = signal<boolean>(false);
  readonly secondsRemaining = signal<number>(0);

  private lastActivity = Date.now();
  private intervalId: ReturnType<typeof setInterval> | null = null;
  private onTimeoutCallback: (() => void) | null = null;
  private isListening = false;
  private lastRecordedMove = 0;

  private readonly activityListener = () => {
    const now = Date.now();
    // Throttle user activity to at most once per second
    if (now - this.lastRecordedMove > 1000) {
      this.lastRecordedMove = now;
      if (!this.warning()) {
        this.lastActivity = now;
      }
    }
  };

  start(onTimeout: () => void): void {
    this.stop();
    this.onTimeoutCallback = onTimeout;
    this.lastActivity = Date.now();
    this.warning.set(false);
    this.secondsRemaining.set(0);

    if (typeof window !== 'undefined') {
      const events = ['keydown', 'mousemove', 'pointerdown', 'touchstart', 'scroll'];
      for (const ev of events) {
        window.addEventListener(ev, this.activityListener, { passive: true });
      }
      this.isListening = true;

      const idleMs = environment.idleMinutes * 60 * 1000;
      const warnMs = environment.idleWarnSeconds * 1000;

      this.intervalId = setInterval(() => {
        const elapsed = Date.now() - this.lastActivity;
        const remainingMs = idleMs - elapsed;

        if (remainingMs <= 0) {
          this.stop();
          this.onTimeoutCallback?.();
        } else if (remainingMs <= warnMs) {
          this.warning.set(true);
          this.secondsRemaining.set(Math.max(1, Math.ceil(remainingMs / 1000)));
        } else {
          if (this.warning()) {
            this.warning.set(false);
            this.secondsRemaining.set(0);
          }
        }
      }, 1000);
    }
  }

  extend(): void {
    this.lastActivity = Date.now();
    this.warning.set(false);
    this.secondsRemaining.set(0);
  }

  stop(): void {
    if (this.intervalId !== null) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
    if (this.isListening && typeof window !== 'undefined') {
      const events = ['keydown', 'mousemove', 'pointerdown', 'touchstart', 'scroll'];
      for (const ev of events) {
        window.removeEventListener(ev, this.activityListener);
      }
      this.isListening = false;
    }
    this.warning.set(false);
    this.secondsRemaining.set(0);
  }
}
