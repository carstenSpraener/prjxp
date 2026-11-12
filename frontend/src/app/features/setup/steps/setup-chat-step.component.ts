import { Component, Input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ChatModelRequest } from '../../../models/setup.model';
import { nonBlank, numeric, urlOrEnv } from '../setup-validators';
import { SetupForm } from './setup-form';
function chatForm() {
  return new FormGroup({
    serverType: new FormControl<string | null>('ollama', nonBlank),
    modelName: new FormControl<string | null>('', nonBlank),
    providerUrl: new FormControl<string | null>('', urlOrEnv),
    apiKey: new FormControl<string | null>(null),
    timeoutSecs: new FormControl<number | null>(60, [Validators.required, numeric(1, true)]),
    temperature: new FormControl<number | null>(0.7, [Validators.required, numeric(0, false, 2)])
  });
}
@Component({selector: 'setup-chat-step', standalone: true, imports: [ReactiveFormsModule],
  templateUrl: './setup-chat-step.component.html', styleUrl: './setup-step.css'})
export class SetupChatStepComponent extends SetupForm<ChatModelRequest, ReturnType<typeof chatForm>['controls']> {
  @Input() serverTypes: string[] = [];
  form = chatForm();
  private patched: ChatModelRequest | null = null;
  protected patch(): void {
    if (this.initial !== this.patched) {
      this.patched = this.initial;
      this.form.patchValue({...this.initial, timeoutSecs: this.initial?.timeoutSecs ?? 60,
        temperature: this.initial?.temperature ?? 0.7}, {emitEvent: false});
    }
    this.form.controls.temperature.setValidators([Validators.required, numeric(0, false, 2)]);
    this.form.controls.temperature.updateValueAndValidity({emitEvent: false});
  }
  configuration(): ChatModelRequest { return {stereoType: 'default', ...this.form.getRawValue()}; }
}
