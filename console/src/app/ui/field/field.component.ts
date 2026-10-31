import {
  Component,
  forwardRef,
  input,
  model,
  signal,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { IconComponent, IconName } from '../icon/icon.component';

export type FieldState = 'focus' | 'error' | null;

@Component({
  selector: 'app-field',
  standalone: true,
  imports: [IconComponent],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => FieldComponent),
      multi: true,
    },
  ],
  template: `
    <div [class]="'field-wrapper' + (error() || state() === 'error' ? ' is-error' : '')">
      @if (label()) {
        <label [attr.for]="id()" class="field-label">{{ label() }}</label>
      }
      <div class="input-box">
        @if (icon()) {
          <app-icon [name]="icon()!" [size]="18" class="field-icon" />
        }
        <input
          [id]="id()"
          [type]="currentType()"
          [placeholder]="placeholder()"
          [disabled]="disabled()"
          [value]="value()"
          (input)="handleInput($event)"
          (blur)="handleBlur()"
          (focus)="handleFocus()"
          autocomplete="off"
        />
        @if (type() === 'password') {
          <button
            type="button"
            class="eye-btn"
            (click)="togglePassword()"
            tabindex="-1"
          >
            <app-icon name="eye" [size]="16" />
          </button>
        }
      </div>
      @if (hint()) {
        <div class="field-hint">{{ hint() }}</div>
      }
    </div>
  `,
  styleUrl: './field.component.css',
})
export class FieldComponent implements ControlValueAccessor {
  readonly id = input<string>(`field-${Math.random().toString(36).substring(2, 9)}`);
  readonly label = input<string | null>(null);
  readonly placeholder = input<string>('');
  readonly type = input<string>('text');
  readonly icon = input<IconName | null>(null);
  readonly hint = input<string | null>(null);
  readonly state = input<FieldState>(null);
  readonly error = input<boolean>(false);
  readonly disabled = input<boolean>(false);

  readonly value = model<string>('');

  readonly showPassword = signal<boolean>(false);

  get currentType(): () => string {
    return () => {
      if (this.type() === 'password') {
        return this.showPassword() ? 'text' : 'password';
      }
      return this.type();
    };
  }

  private onChange: (val: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: string | null): void {
    this.value.set(value ?? '');
  }

  registerOnChange(fn: (val: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  handleInput(event: Event): void {
    const val = (event.target as HTMLInputElement).value;
    this.value.set(val);
    this.onChange(val);
  }

  handleBlur(): void {
    this.onTouched();
  }

  handleFocus(): void {}

  togglePassword(): void {
    this.showPassword.update((prev) => !prev);
  }
}
