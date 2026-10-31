import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TPipe } from '../../core/i18n/t.pipe';
import { CardComponent } from '../../ui/card/card.component';
import { EmptyStateComponent } from '../../ui/empty-state/empty-state.component';

@Component({
  selector: 'app-placeholder',
  standalone: true,
  imports: [TPipe, CardComponent, EmptyStateComponent],
  template: `
    <app-card>
      <app-empty-state
        icon="clock"
        [title]="'placeholder.title' | t: { step: step }"
        [description]="'placeholder.desc' | t"
      />
    </app-card>
  `,
  styles: `
    :host {
      display: block;
      width: 100%;
    }
  `,
})
export class PlaceholderComponent {
  private readonly route = inject(ActivatedRoute);

  get step(): number {
    return this.route.snapshot.data['step'] ?? 8;
  }
}
