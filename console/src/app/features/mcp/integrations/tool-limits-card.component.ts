import { Component, input, output } from '@angular/core';
import { CardComponent } from '../../../ui/card/card.component';
import { IntegrationView, LimitsChange, ToolView } from './integrations.model';
import { ToolLimitsFormComponent } from './tool-limits-form.component';

@Component({
  selector: 'app-tool-limits-card',
  standalone: true,
  imports: [CardComponent, ToolLimitsFormComponent],
  template: `
    <app-card [label]="tool().name" icon="shield">
      <app-tool-limits-form
        [tool]="tool()"
        [integration]="integration()"
        [canChange]="canChange()"
        [saving]="saving()"
        (save)="save.emit($event)"
        (reset)="reset.emit()"
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
export class ToolLimitsCardComponent {
  readonly tool = input.required<ToolView>();
  readonly integration = input<IntegrationView['integration'] | null>(null);
  readonly canChange = input<boolean>(true);
  readonly saving = input<boolean>(false);

  readonly save = output<LimitsChange>();
  readonly reset = output<void>();
}
