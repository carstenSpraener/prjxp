import { Component, Input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { OptimizationRequest } from '../../../models/setup.model';
import { numeric } from '../setup-validators';
import { SetupForm } from './setup-form';
function optimizationForm() {
  return new FormGroup({
    vectorDimension: new FormControl<number | null>(null, numeric(1, true)),
    maxSegmentsPerBatch: new FormControl<number | null>(32, [Validators.required, numeric(1, true)]),
    embeddingTimeout: new FormControl<number | null>(20, [Validators.required, numeric(1, true)]),
    docpipeMaxThreads: new FormControl<number | null>(4, [Validators.required, numeric(1, true)]),
    readerMaxOutputChars: new FormControl<number | null>(0, [Validators.required, numeric(0, true)]),
    graphRerankFloor: new FormControl<number | null>(0.5, [Validators.required, numeric(0, false, 1)]),
    graphRerankBGraph: new FormControl<number | null>(1, [Validators.required, numeric(0)]),
    graphRerankCap: new FormControl<number | null>(2, [Validators.required, numeric(Number.MIN_VALUE)]),
    graphRerankWBase: new FormControl<number | null>(1, [Validators.required, numeric(0)])
  });
}
@Component({selector: 'setup-optimization-step', standalone: true, imports: [ReactiveFormsModule],
  templateUrl: './setup-optimization-step.component.html', styleUrl: './setup-step.css'})
export class SetupOptimizationStepComponent extends SetupForm<OptimizationRequest, ReturnType<typeof optimizationForm>['controls']> {
  @Input() measuredDimension: number | null = null;
  @Input() retestRequired = false;
  form = optimizationForm();
  private patched: OptimizationRequest | null = null;
  protected patch(): void {
    if (this.initial !== this.patched) {
      this.patched = this.initial;
      const values = this.initial ?? {};
      for (const key of Object.keys(this.form.controls) as (keyof typeof this.form.controls)[]) {
        if (values[key] != null) this.form.controls[key].setValue(values[key], {emitEvent: false});
      }
    }
    this.form.setValidators(() => this.measuredDimension == null && !this.retestRequired ? {dimensionUnknown: true} :
      this.measuredDimension != null && this.form.controls.vectorDimension.value != null &&
      this.form.controls.vectorDimension.value !== this.measuredDimension ? {dimensionMismatch: true} : null);
    this.form.updateValueAndValidity({emitEvent: false});
  }
  configuration(): OptimizationRequest {
    const value = this.form.getRawValue();
    return {...value, vectorDimension: value.vectorDimension ?? this.measuredDimension};
  }
}
