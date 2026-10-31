import {
  Component,
  forwardRef,
  input,
  model,
  output,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

@Component({
  selector: 'app-toggle',
  standalone: true,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => ToggleComponent),
      multi: true,
    },
  ],
  template: `
    <button
      type="button"
      role="switch"
      [attr.aria-checked]="checked()"
      [disabled]="disabled()"
      [class]="'toggle-track' + (checked() ? ' is-on' : '')"
      (click)="toggle()"
    >
      <span class="toggle-knob"></span>
    </button>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
    }
    .toggle-track {
      width: 36px;
      height: 20px;
      border-radius: var(--radius-pill);
      background-color: var(--line);
      padding: 2px;
      border: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      transition: background-color var(--dur-fast) var(--ease);
      box-sizing: border-box;
    }
    .toggle-track.is-on {
      background-color: var(--green-bright);
    }
    .toggle-track:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .toggle-knob {
      width: 16px;
      height: 16px;
      border-radius: var(--radius-pill);
      background-color: var(--bg-tile);
      transition: transform var(--dur-fast) var(--ease);
      transform: translateX(0);
      display: block;
    }
    .toggle-track.is-on .toggle-knob {
      transform: translateX(16px);
    }
  `,
})
export class ToggleComponent implements ControlValueAccessor {
  readonly checked = model<boolean>(false);
  readonly disabled = input<boolean>(false);
  readonly changed = output<boolean>();

  private onChange: (val: boolean) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(val: boolean | null): void {
    this.checked.set(!!val);
  }

  registerOnChange(fn: (val: boolean) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  toggle(): void {
    if (this.disabled()) return;
    const next = !this.checked();
    this.checked.set(next);
    this.onChange(next);
    this.onTouched();
    this.changed.emit(next);
  }
}
