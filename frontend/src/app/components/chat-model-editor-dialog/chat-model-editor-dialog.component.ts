import { Component, Input, Output, EventEmitter, OnInit, OnChanges, OnDestroy, ChangeDetectionStrategy } from '@angular/core';
import { ReactiveFormsModule, FormControl, FormGroup, FormArray, Validators } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { ModelTestResult } from '../../models/model-test-result.model';
import { GlobalsService } from '../../services/globals.service';
import { SetupService } from '../../services/setup.service';
import { nonBlank, numeric, urlOrEnv, setupError } from '../../features/setup/setup-validators';
function argumentForm(key = '', value = '') {
  return new FormGroup({key: new FormControl(key, {nonNullable: true}), value: new FormControl(value, {nonNullable: true})});
}
@Component({selector: 'chat-model-editor-dialog', standalone: true, imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.Default,
  templateUrl: './chat-model-editor-dialog.component.html', styleUrl: './chat-model-editor-dialog.component.css'})
export class ChatModelEditorDialogComponent implements OnInit, OnChanges, OnDestroy {
  @Input() model: ChatModelReference | null | undefined = null;
  @Input() busy = false;
  @Input() saveError: string | null = null;
  @Input() configVersion = '';
  @Output() save = new EventEmitter<ChatModelReference>();
  @Output() close = new EventEmitter<void>();
  @Output() versionChange = new EventEmitter<string>();
  form = new FormGroup({
    stereoType: new FormControl('', {nonNullable: true, validators: nonBlank}),
    serverType: new FormControl('ollama', {nonNullable: true, validators: nonBlank}),
    modelName: new FormControl('', {nonNullable: true, validators: nonBlank}),
    providerUrl: new FormControl('', {nonNullable: true, validators: urlOrEnv}),
    apiKey: new FormControl<string | null>(null),
    timeoutSecs: new FormControl(60, {nonNullable: true, validators: [Validators.required, numeric(1, true)]}),
    temperature: new FormControl(0.7, {nonNullable: true, validators: [Validators.required, numeric(0, false, 2)]}),
    prompt: new FormControl('', {nonNullable: true}),
    extraArgs: new FormArray<ReturnType<typeof argumentForm>>([])
  });
  isEditing = false;
  testResult: ModelTestResult | null = null;
  testLoading = false;
  listLoading = false;
  listError: string | null = null;
  availableModels: string[] = [];
  serverTypeOptions = [{value: 'ollama', label: 'Ollama'}, {value: 'openapi', label: 'OpenAI'},
    {value: 'lm-studio', label: 'LM Studio'}, {value: 'gemini', label: 'Gemini'}, {value: 'custom', label: 'Custom'}];
  private revision = 0;
  private requestId = 0;
  private subscriptions = new Subscription();
  private patched: ChatModelReference | null | undefined;
  constructor(private globalsService: GlobalsService, private setup: SetupService) {}
  ngOnInit(): void {
    this.patch();
    this.subscriptions.add(this.form.valueChanges.subscribe(() => {this.revision++; this.testResult = null;}));
    this.subscriptions.add(this.globalsService.getServerTypes().subscribe({
      next: types => this.serverTypeOptions = types.map(value => ({value, label: value})), error: () => {}
    }));
  }
  ngOnChanges(): void {
    this.patch();
    this.busy || this.testLoading ? this.form.disable({emitEvent: false}) : this.form.enable({emitEvent: false});
  }
  private patch(): void {
    if (this.patched === this.model) return;
    this.patched = this.model;
    this.isEditing = !!this.model?.stereoType;
    this.form.patchValue({stereoType: this.model?.stereoType ?? '', serverType: this.model?.serverType ?? 'ollama',
      modelName: this.model?.modelName ?? '', providerUrl: this.model?.providerUrl ?? '',
      apiKey: this.model?.apiKey ?? null, timeoutSecs: this.model?.timeoutSecs ?? 60,
      temperature: this.model?.temperature ?? 0.7, prompt: this.model?.prompt ?? ''}, {emitEvent: false});
    this.extraArgsArray.clear({emitEvent: false});
    for (const [key, value] of Object.entries(this.model?.args ?? {}))
      this.extraArgsArray.push(argumentForm(key, value), {emitEvent: false});
  }
  get extraArgsArray() { return this.form.controls.extraArgs; }
  addExtraArg(key = '', value = ''): void { if (!this.busy && !this.testLoading) this.extraArgsArray.push(argumentForm(key, value)); }
  removeExtraArg(index: number): void { if (!this.busy && !this.testLoading) this.extraArgsArray.removeAt(index); }
  showApiKey(): boolean { return ['openapi', 'openai', 'gemini', 'custom'].includes(this.form.controls.serverType.value); }
  showExtraArgs(): boolean { return this.form.controls.serverType.value === 'custom'; }
  configuration(): ChatModelReference {
    const value = this.form.getRawValue();
    const args: Record<string, string> = {};
    for (const arg of value.extraArgs) if (arg.key) args[arg.key] = arg.value;
    return {stereoType: value.stereoType, serverType: value.serverType, modelName: value.modelName,
      providerUrl: value.providerUrl, apiKey: value.apiKey, timeoutSecs: value.timeoutSecs,
      temperature: value.temperature, prompt: value.prompt || null, args};
  }
  onSave(): void {
    if (this.busy || this.testLoading || this.listLoading || !this.configVersion || this.form.invalid) {
      this.form.markAllAsTouched(); return;
    }
    this.save.emit(this.configuration());
  }
  onTest(): void {
    if (this.busy || this.testLoading || this.listLoading || !this.configVersion || this.form.invalid) return;
    const revision = this.revision, id = ++this.requestId;
    this.testLoading = true; this.testResult = null;
    const candidate = this.configuration();
    this.form.disable({emitEvent: false});
    this.subscriptions.add(this.globalsService.testChatModel(candidate, this.configVersion).subscribe({
      next: result => {
        if (id !== this.requestId) return;
        this.configVersion = this.globalsService.chatVersion ?? this.configVersion;
        this.versionChange.emit(this.configVersion);
        this.testLoading = false;
        if (!this.busy) this.form.enable({emitEvent: false});
        if (revision === this.revision) this.testResult = {...result,
          success: result.success && !!result.response?.trim(), message: result.success ? 'Chat-Antwort bestätigt' : 'Chat-Test fehlgeschlagen'};
        this.subscriptions.add(this.setup.getStatus().subscribe({error: () => {}}));
      },
      error: error => {
        this.testLoading = false;
        if (!this.busy) this.form.enable({emitEvent: false});
        this.testResult = {success: false, message: setupError(error), latencyMs: 0, response: null, dimensions: null};
      }
    }));
  }
  onReadModels(): void {
    if (this.busy || this.testLoading || this.listLoading || this.form.controls.providerUrl.invalid) return;
    const value = this.configuration();
    this.listLoading = true; this.listError = null;
    this.subscriptions.add(this.globalsService.listChatModels({serverType: value.serverType ?? '',
      providerUrl: value.providerUrl ?? '', apiKey: value.apiKey ?? '', stereoType: value.stereoType ?? ''}).subscribe({
      next: models => {this.availableModels = models; this.listLoading = false;},
      error: error => {this.listError = setupError(error); this.listLoading = false;}
    }));
  }
  onClose(): void {
    if (this.busy || this.testLoading || this.listLoading || this.form.dirty && !window.confirm('Ungespeicherte Eingaben verwerfen?')) return;
    this.close.emit();
  }
  ngOnDestroy(): void { this.requestId++; this.subscriptions.unsubscribe(); }
}
