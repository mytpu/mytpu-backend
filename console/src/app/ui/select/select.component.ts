import {
  Component,
  ElementRef,
  booleanAttribute,
  computed,
  forwardRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CdkConnectedOverlay, CdkOverlayOrigin, ConnectedPosition } from '@angular/cdk/overlay';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { IconComponent, IconName } from '../icon/icon.component';

export interface SelectOption<T = string> {
  value: T;
  label: string;
  /** Подпись справа серым: код, количество */
  hint?: string;
}

let nextId = 0;

/**
 * Выпадающий список консоли вместо нативного select: меню нативного рисует браузер и оно не совпадает с темами.
 * Фокус всегда остаётся на кнопке, активный пункт — через aria-activedescendant.
 */
@Component({
  selector: 'app-select',
  standalone: true,
  imports: [CdkConnectedOverlay, CdkOverlayOrigin, IconComponent],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SelectComponent), multi: true }],
  templateUrl: './select.component.html',
  styleUrl: './select.component.css',
  host: {
    '[class.size-sm]': "size() === 'sm'",
    '[class.is-block]': 'block()',
  },
})
export class SelectComponent<T = string> implements ControlValueAccessor {
  readonly options = input.required<readonly SelectOption<T>[]>();
  readonly placeholder = input<string>('—');
  readonly icon = input<IconName | null>(null);
  readonly size = input<'sm' | 'md'>('md');
  /** Растянуть на ширину контейнера (поля форм) */
  readonly block = input(false, { transform: booleanAttribute });
  readonly ariaLabel = input<string | null>(null);
  readonly disabledInput = input(false, { alias: 'disabled', transform: booleanAttribute });

  /** Для использования без форм: [value] и (valueChange) */
  readonly valueInput = input<T | undefined>(undefined, { alias: 'value' });
  readonly valueChange = output<T>();

  readonly open = signal(false);
  readonly active = signal(-1);
  private readonly formValue = signal<{ v: T | undefined } | null>(null);
  private readonly formDisabled = signal(false);

  readonly id = `app-select-${nextId++}`;
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  readonly triggerWidth = signal(0);

  readonly value = computed(() => {
    const f = this.formValue();
    return f ? f.v : this.valueInput();
  });
  readonly disabled = computed(() => this.disabledInput() || this.formDisabled());
  readonly selectedIndex = computed(() => this.options().findIndex((o) => o.value === this.value()));
  readonly selected = computed<SelectOption<T> | null>(() => this.options()[this.selectedIndex()] ?? null);

  readonly positions: ConnectedPosition[] = [
    { originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 6 },
    { originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -6 },
    { originX: 'end', originY: 'bottom', overlayX: 'end', overlayY: 'top', offsetY: 6 },
  ];

  private typed = '';
  private typedAt = 0;
  private onChange: (v: T) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(v: T): void {
    this.formValue.set({ v });
  }

  registerOnChange(fn: (v: T) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.formDisabled.set(disabled);
  }

  toggle(): void {
    if (this.open()) {
      this.close();
    } else {
      this.show();
    }
  }

  show(): void {
    if (this.disabled() || this.options().length === 0) return;
    this.triggerWidth.set(this.trigger().nativeElement.getBoundingClientRect().width);
    this.active.set(Math.max(0, this.selectedIndex()));
    this.open.set(true);
  }

  close(): void {
    if (!this.open()) return;
    this.open.set(false);
    this.onTouched();
  }

  /** Клик вне меню; клик по самой кнопке обработает toggle */
  outsideClick(event: MouseEvent): void {
    if (!this.trigger().nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  choose(index: number): void {
    const option = this.options()[index];
    if (!option) return;
    this.close();
    this.trigger().nativeElement.focus();
    if (option.value === this.value()) return;
    if (this.formValue()) this.formValue.set({ v: option.value });
    this.onChange(option.value);
    this.valueChange.emit(option.value);
  }

  onKeydown(event: KeyboardEvent): void {
    const count = this.options().length;
    if (this.disabled() || count === 0) return;
    const open = this.open();
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        event.preventDefault();
        if (!open) {
          this.show();
          return;
        }
        const step = event.key === 'ArrowDown' ? 1 : -1;
        this.moveActive(Math.min(count - 1, Math.max(0, this.active() + step)));
        return;
      }
      case 'Home':
      case 'End':
        if (!open) return;
        event.preventDefault();
        this.moveActive(event.key === 'Home' ? 0 : count - 1);
        return;
      case 'Enter':
      case ' ':
        event.preventDefault();
        if (open) {
          this.choose(this.active());
        } else {
          this.show();
        }
        return;
      case 'Escape':
        if (open) {
          event.preventDefault();
          event.stopPropagation();
          this.close();
        }
        return;
      case 'Tab':
        this.close();
        return;
      default:
        if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
          this.jumpTo(event.key);
        }
    }
  }

  optionId(index: number): string {
    return `${this.id}-opt-${index}`;
  }

  /** Ввод букв: переход к пункту, начинающемуся с набранного (пауза 700 мс сбрасывает набор) */
  private jumpTo(char: string): void {
    const now = Date.now();
    this.typed = now - this.typedAt > 700 ? char : this.typed + char;
    this.typedAt = now;
    const query = this.typed.toLocaleLowerCase();
    const index = this.options().findIndex((o) => o.label.toLocaleLowerCase().startsWith(query));
    if (index < 0) return;
    if (this.open()) {
      this.moveActive(index);
    } else {
      this.choose(index);
    }
  }

  private moveActive(index: number): void {
    this.active.set(index);
    queueMicrotask(() => document.getElementById(this.optionId(index))?.scrollIntoView({ block: 'nearest' }));
  }
}
