import { Component, Input, Output, EventEmitter, OnChanges, ChangeDetectionStrategy } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { EmbeddingConfig } from '../../models/embedding-config.model';
import { SetupEmbeddingStepComponent } from '../../features/setup/steps/setup-embedding-step.component';
import { SetupTestResult } from '../../models/setup.model';
import { GlobalsService } from '../../services/globals.service';
import { SetupService } from '../../services/setup.service';
import { setupError } from '../../features/setup/setup-validators';

@Component({selector: 'embedding-model-editor-dialog', standalone: true, imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.Default,
  templateUrl: './embedding-model-editor-dialog.component.html', styleUrl: './embedding-model-editor-dialog.component.css'})
export class EmbeddingModelEditorDialogComponent extends SetupEmbeddingStepComponent implements OnChanges {
  @Input() model: EmbeddingConfig | null = null;
  @Input() configVersion = '';
  @Input() saveError: string | null = null;
  @Output() save = new EventEmitter<EmbeddingConfig>();
  @Output() close = new EventEmitter<void>();
  @Output() versionChange = new EventEmitter<string>();
  testResult: SetupTestResult | null = null;
  testId: string | null = null;
  testLoading = false;
  listLoading = false;
  listError: string | null = null;
  availableModels: string[] = [];
  private revision = 0;
  private requestId = 0;
  private subscriptions = new Subscription();
  private connected = false;
  constructor(private globals: GlobalsService, private setup: SetupService) { super(); }
  override ngOnChanges(): void {
    this.initial = this.model;
    super.ngOnChanges();
    if (!this.connected) {
      this.connected = true;
      this.subscriptions.add(this.form.valueChanges.subscribe(() => {
        this.revision++; this.testId = null; this.testResult = null;
      }));
    }
    if (this.testLoading) this.form.disable({emitEvent: false});
  }
  showApiKey(): boolean { return this.form.controls.type.value === 'OPEN_AI'; }
  onSave(): void {
    if (this.busy || this.testLoading || this.listLoading || !this.configVersion || this.form.invalid) {
      this.form.markAllAsTouched(); return;
    }
    this.save.emit(this.configuration());
  }
  onTest(): void {
    if (this.busy || this.testLoading || this.listLoading || !this.configVersion || this.form.invalid) return;
    const revision = this.revision, id = ++this.requestId;
    this.testLoading = true; this.testResult = null; this.testId = null;
    const configuration = this.configuration();
    this.form.disable({emitEvent: false});
    this.subscriptions.add(this.globals.testEmbeddingConfig(configuration, this.configVersion).subscribe({
      next: result => {
        if (id !== this.requestId) return;
        this.configVersion = result.configVersion; this.versionChange.emit(result.configVersion);
        this.testLoading = false;
        if (!this.busy) this.form.enable({emitEvent: false});
        if (revision === this.revision) {
          this.testResult = result;
          this.testId = result.success && result.dimensions != null && result.dimensions > 0 &&
            Number.isInteger(result.dimensions) && (configuration.dimensions == null || configuration.dimensions === result.dimensions) ?
            result.testId : null;
        }
        this.refreshStatus();
      },
      error: error => {
        this.testLoading = false;
        if (!this.busy) this.form.enable({emitEvent: false});
        this.testResult = {success: false, message: setupError(error), latencyMs: 0, response: null,
          dimensions: null, testId: null, configVersion: this.configVersion};
      }
    }));
  }
  onReadModels(): void {
    const value = this.configuration();
    if (this.busy || this.testLoading || this.listLoading || this.form.controls.apiBaseURL.invalid) return;
    this.listLoading = true; this.listError = null;
    this.subscriptions.add(this.globals.listEmbeddingModels({serverType: value.type === 'OPEN_AI' ? 'openai' : 'ollama',
      providerUrl: value.apiBaseURL ?? '', apiKey: value.apiKey ?? ''}).subscribe({
      next: models => {this.availableModels = models; this.listLoading = false;},
      error: error => {this.listError = setupError(error); this.listLoading = false;}
    }));
  }
  onClose(): void {
    if (this.busy || this.testLoading || this.listLoading) return;
    if (this.form.dirty && !window.confirm('Ungespeicherte Eingaben verwerfen?')) return;
    this.close.emit();
  }
  private refreshStatus(): void { this.subscriptions.add(this.setup.getStatus().subscribe({error: () => {}})); }
  override ngOnDestroy(): void { this.requestId++; this.subscriptions.unsubscribe(); super.ngOnDestroy(); }
}
