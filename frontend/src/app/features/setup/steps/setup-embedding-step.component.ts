import { Component } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { EmbeddingConfigRequest } from '../../../models/setup.model';
import { nonBlank, numeric, urlOrEnv } from '../setup-validators';
import { SetupForm } from './setup-form';
export function embeddingForm() {
  return new FormGroup({
    type: new FormControl<string | null>('OLLAMA', [Validators.required, Validators.pattern(/^(OLLAMA|OPEN_AI)$/)]),
    apiBaseURL: new FormControl<string | null>('', urlOrEnv),
    modelName: new FormControl<string | null>('', nonBlank),
    apiKey: new FormControl<string | null>(null),
    dimensions: new FormControl<number | null>(null, numeric(1, true)),
    timeout: new FormControl<number | null>(20, [Validators.required, numeric(1, true)]),
    maxSegmentsPerBatch: new FormControl<number | null>(32, [Validators.required, numeric(1, true)])
  });
}
@Component({selector: 'setup-embedding-step', standalone: true, imports: [ReactiveFormsModule],
  templateUrl: './setup-embedding-step.component.html', styleUrl: './setup-step.css'})
export class SetupEmbeddingStepComponent extends SetupForm<EmbeddingConfigRequest, ReturnType<typeof embeddingForm>['controls']> {
  form = embeddingForm();
  private patched: EmbeddingConfigRequest | null = null;
  protected patch(): void {
    if (this.initial === this.patched) return;
    this.patched = this.initial;
    this.form.patchValue({...this.initial, timeout: this.initial?.timeout ?? 20,
      maxSegmentsPerBatch: this.initial?.maxSegmentsPerBatch ?? 32}, {emitEvent: false});
  }
  configuration(): EmbeddingConfigRequest {
    const value = this.form.getRawValue();
    return {...value, resetDimensions: value.dimensions == null &&
      (!!this.initial?.resetDimensions || this.initial?.dimensions != null)};
  }
}
