import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators, AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { EmbeddingModelReference } from '../../models/embedding-model-reference.model';

const ENV_PLACEHOLDER_PATTERN = /^\$\{[A-Za-z0-9_.-]+(?::[^}]*)?}$/;
const URL_PATTERN = /^https?:\/\/.+/;
const MASKED_SECRET = '********';

function urlOrEnvValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = (control.value ?? '').toString().trim();
    if (!value) {
      return { required: true };
    }
    if (ENV_PLACEHOLDER_PATTERN.test(value) || URL_PATTERN.test(value)) {
      return null;
    }
    return { urlOrEnv: true };
  };
}

@Component({
  selector: 'embedding-model-editor-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './embedding-model-editor-dialog.component.html',
  styleUrl: './embedding-model-editor-dialog.component.css'
})
export class EmbeddingModelEditorDialogComponent implements OnInit {
  @Input() model: EmbeddingModelReference | null = null;
  @Output() save = new EventEmitter<EmbeddingModelReference>();
  @Output() close = new EventEmitter<void>();

  form!: FormGroup;

  serverTypeOptions = [
    { value: 'ollama', label: 'Ollama' },
    { value: 'openai', label: 'OpenAI' },
    { value: 'lm-studio', label: 'LM Studio' },
    { value: 'gemini', label: 'Gemini' },
    { value: 'custom', label: 'Custom' }
  ];

  constructor(private fb: FormBuilder) {}

  ngOnInit(): void {
    this.form = this.fb.group({
      serverType: [this.model?.serverType ?? 'ollama', [Validators.required]],
      providerUrl: [this.model?.providerUrl ?? '', [urlOrEnvValidator()]],
      modelName: [this.model?.modelName ?? '', [Validators.required]],
      apiKey: [this.model?.apiKey ?? ''],
      timeoutSecs: [this.model?.timeoutSecs ?? 60, [Validators.required, Validators.min(10)]]
    });

    // Show/hide API key based on server type
    this.form.get('serverType')?.valueChanges.subscribe(() => {
      this.updateConditionalFields();
    });

    // Initialize conditional fields visibility
    this.updateConditionalFields();
  }

  private updateConditionalFields(): void {
    // No action needed; apiKey is always visible but optional
  }

  showApiKey(): boolean {
    const type = this.form.get('serverType')?.value;
    return ['openai', 'gemini', 'custom'].includes(type);
  }

  onSave(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.value;
    const result: EmbeddingModelReference = {
      serverType: value.serverType,
      providerUrl: value.providerUrl,
      modelName: value.modelName,
      apiKey: this.model
        ? (value.apiKey === MASKED_SECRET ? MASKED_SECRET : (value.apiKey || ''))
        : (value.apiKey || null),
      timeoutSecs: value.timeoutSecs
    };

    this.save.emit(result);
  }

  onClose(): void {
    this.close.emit();
  }
}
