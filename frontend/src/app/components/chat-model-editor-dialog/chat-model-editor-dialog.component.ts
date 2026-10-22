import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators, FormArray, AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { ModelTestResult } from '../../models/model-test-result.model';
import { GlobalsService } from '../../services/globals.service';

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
  selector: 'chat-model-editor-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './chat-model-editor-dialog.component.html',
  styleUrl: './chat-model-editor-dialog.component.css'
})
export class ChatModelEditorDialogComponent implements OnInit {
  @Input() model: ChatModelReference | null | undefined = null;
  @Output() save = new EventEmitter<ChatModelReference>();
  @Output() close = new EventEmitter<void>();

  form!: FormGroup;
  isEditing = false;

  testResult: ModelTestResult | null = null;
  testLoading = false;
  listLoading = false;
  listError: string | null = null;
  availableModels: string[] = [];

  serverTypeOptions = [
    { value: 'ollama', label: 'Ollama' },
    { value: 'openapi', label: 'OpenAI' },
    { value: 'lm-studio', label: 'LM Studio' },
    { value: 'gemini', label: 'Gemini' },
    { value: 'custom', label: 'Custom' }
  ];

  constructor(private fb: FormBuilder, private globalsService: GlobalsService) {}

  ngOnInit(): void {
    this.isEditing = this.model !== null && this.model?.stereoType !== undefined;

    this.form = this.fb.group({
      stereoType: [this.model?.stereoType ?? '', [Validators.required]],
      serverType: [this.model?.serverType ?? 'ollama', [Validators.required]],
      modelName: [this.model?.modelName ?? '', [Validators.required]],
      providerUrl: [this.model?.providerUrl ?? '', [urlOrEnvValidator()]],
      apiKey: [this.model?.apiKey ?? ''],
      timeoutSecs: [this.model?.timeoutSecs ?? 60, [Validators.required, Validators.min(10)]],
      temperature: [this.model?.temperature ?? 0.7, [Validators.required, Validators.min(0), Validators.max(2)]],
      prompt: [this.model?.prompt ?? ''],
      extraArgs: this.fb.array([])
    });

    // Initialize extra args from existing model
    if (this.model?.args && Object.keys(this.model.args).length > 0) {
      for (const [key, value] of Object.entries(this.model.args)) {
        this.addExtraArg(key, value);
      }
    }

    // Show/hide API key and extra args based on server type
    this.form.get('serverType')?.valueChanges.subscribe(() => {
      this.updateConditionalFields();
    });

    // Initialize conditional fields visibility
    this.updateConditionalFields();
  }

  private updateConditionalFields(): void {
    // No longer hide/clear apiKey - it's always visible and optional
  }

  get extraArgsArray(): FormArray {
    return this.form.get('extraArgs') as FormArray;
  }

  addExtraArg(key = '', value = ''): void {
    this.extraArgsArray.push(this.fb.group({ key, value }));
  }

  removeExtraArg(index: number): void {
    this.extraArgsArray.removeAt(index);
  }

  showApiKey(): boolean {
    const type = this.form.get('serverType')?.value;
    return ['openapi', 'gemini', 'custom'].includes(type);
  }

  showExtraArgs(): boolean {
    return this.form.get('serverType')?.value === 'custom';
  }

  onSave(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.value;
    const args: Record<string, string> = {};
    for (const arg of this.extraArgsArray.controls) {
      const aVal = arg.value as { key: string; value: string };
      if (aVal.key) {
        args[aVal.key] = aVal.value;
      }
    }

    const result: ChatModelReference = {
      stereoType: value.stereoType,
      serverType: value.serverType,
      modelName: value.modelName,
      apiKey: this.isEditing
        ? (value.apiKey === MASKED_SECRET ? MASKED_SECRET : (value.apiKey || ''))
        : (value.apiKey || null),
      providerUrl: value.providerUrl,
      timeoutSecs: value.timeoutSecs,
      temperature: value.temperature,
      prompt: value.prompt || null,
      args: this.showExtraArgs() ? args : {}
    };

    this.save.emit(result);
  }

  onTest(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.value;
    this.testLoading = true; this.testResult = null;
    const model: ChatModelReference = {
      stereoType: v.stereoType, serverType: v.serverType, modelName: v.modelName,
      apiKey: v.apiKey || null, providerUrl: v.providerUrl,
      timeoutSecs: v.timeoutSecs, temperature: v.temperature
    };
    this.globalsService.testChatModel(model).subscribe({
      next: (r) => { this.testResult = r; this.testLoading = false; },
      error: (e) => {
        this.testResult = { success: false, message: e?.error?.message ?? e?.message ?? 'Test failed', latencyMs: 0, response: null, dimensions: null };
        this.testLoading = false;
      }
    });
  }

  onReadModels(): void {
    const v = this.form.value;
    this.listLoading = true; this.listError = null;
    this.globalsService.listChatModels({
      serverType: v.serverType, providerUrl: v.providerUrl,
      apiKey: v.apiKey || '', stereoType: v.stereoType
    }).subscribe({
      next: (models) => { this.availableModels = models; this.listLoading = false; },
      error: (e) => { this.listError = e?.error?.message ?? e?.message ?? 'Failed to load models'; this.listLoading = false; }
    });
  }

  onClose(): void {
    this.close.emit();
  }
}
