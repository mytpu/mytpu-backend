import { Component, input, output } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { TPipe } from '../../../../core/i18n/t.pipe';
import { ButtonComponent } from '../../../../ui/button/button.component';
import { IconComponent } from '../../../../ui/icon/icon.component';
import {
  createParamGroup,
  ToolForm,
  updateToolPathValidation,
} from './connector-editor.model';
import { EditorParamRowComponent } from './editor-param-row.component';
import { EditorResultComponent } from './editor-result.component';

@Component({
  selector: 'app-editor-tool',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    TPipe,
    ButtonComponent,
    IconComponent,
    EditorParamRowComponent,
    EditorResultComponent,
  ],
  templateUrl: './editor-tool.component.html',
  styleUrl: './editor-tool.component.css',
})
export class EditorToolComponent {
  readonly group = input.required<FormGroup<ToolForm>>();
  readonly index = input.required<number>();
  readonly submitted = input<boolean>(false);
  readonly canChange = input<boolean>(false);

  readonly remove = output<number>();
  readonly changed = output<void>();

  onPathChanged(): void {
    updateToolPathValidation(this.group());
    this.changed.emit();
  }

  onParamChanged(): void {
    updateToolPathValidation(this.group());
    this.changed.emit();
  }

  addParam(): void {
    this.group().controls.params.push(createParamGroup());
    updateToolPathValidation(this.group());
    this.changed.emit();
  }

  removeParam(idx: number): void {
    this.group().controls.params.removeAt(idx);
    updateToolPathValidation(this.group());
    this.changed.emit();
  }

  hasError(ctrl: AbstractControl): boolean {
    return ctrl.invalid && (ctrl.touched || ctrl.dirty || this.submitted());
  }

  /** Имена через запятую: параметры из пути без объявления in: path и объявленные in: path, которых нет в пути. */
  pathMismatch(): { notDeclared: string; notInPath: string } {
    const pathVal = (this.group().controls.path.value || '').trim();
    const pathMatches = Array.from(pathVal.matchAll(/\{([a-zA-Z0-9_]+)\}/g)).map((m) => m[1]);
    const pathSet = new Set(pathMatches);
    const declared = new Set<string>();
    const paramsArray = this.group().controls.params;
    for (let i = 0; i < paramsArray.length; i++) {
      const p = paramsArray.at(i);
      if (p.controls.in.value === 'path' && p.controls.name.value.trim()) {
        declared.add(p.controls.name.value.trim());
      }
    }
    const missingInParams = [...pathSet].filter((p) => !declared.has(p));
    const missingInPath = [...declared].filter((p) => !pathSet.has(p));
    return { notDeclared: missingInParams.join(', '), notInPath: missingInPath.join(', ') };
  }
}
