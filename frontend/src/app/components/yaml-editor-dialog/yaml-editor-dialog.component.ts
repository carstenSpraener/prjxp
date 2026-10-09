import { Component, Input, Output, EventEmitter } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { PrjxpYamlConfig, DEFAULT_YAML_CONFIG } from '../../models/prjxp-yaml-config.model';
import { ProjectInfo } from '../../models/project-info.model';

@Component({
  selector: 'yaml-editor-dialog',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './yaml-editor-dialog.component.html',
  styleUrl: './yaml-editor-dialog.component.css'
})
export class YamlEditorDialogComponent {
  @Output() save = new EventEmitter<PrjxpYamlConfig>();
  @Output() close = new EventEmitter<void>();

  @Input() set projectToEdit(project: ProjectInfo | null) {
    if (project) {
      this.isEditing = true;
      this.form.patchValue({
        name: project.name,
        rootDir: project.rootDir || DEFAULT_YAML_CONFIG.rootDir,
        jsonlFile: project.jsonlFile || DEFAULT_YAML_CONFIG.jsonlFile,
        chunoWhiteList: project.chunoWhiteList || DEFAULT_YAML_CONFIG.chunoWhiteList,
        tibedBatchSize: project.tibedBatchSize ?? DEFAULT_YAML_CONFIG.tibedBatchSize,
        embeddingsFile: project.embeddingsFile || null,
        tibedResetStore: project.tibedResetStore ?? DEFAULT_YAML_CONFIG.tibedResetStore,
        prompt: project.prompt || ''
      });
    } else {
      this.isEditing = false;
      this.form.reset({
        name: '',
        ...DEFAULT_YAML_CONFIG,
        prompt: ''
      });
    }
  }

  form: FormGroup;

  isEditing = false;

  constructor(private fb: FormBuilder) {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(1)]],
      rootDir: [DEFAULT_YAML_CONFIG.rootDir],
      jsonlFile: [DEFAULT_YAML_CONFIG.jsonlFile],
      chunoWhiteList: [DEFAULT_YAML_CONFIG.chunoWhiteList],
      tibedBatchSize: [DEFAULT_YAML_CONFIG.tibedBatchSize],
      embeddingsFile: [DEFAULT_YAML_CONFIG.embeddingsFile],
      tibedResetStore: [DEFAULT_YAML_CONFIG.tibedResetStore],
      prompt: ['']
    });
  }

  onSave(): void {
    if (this.form.valid) {
      const config: PrjxpYamlConfig = {
        name: this.form.get('name')!.value,
        rootDir: this.form.get('rootDir')!.value || undefined,
        jsonlFile: this.form.get('jsonlFile')!.value || undefined,
        chunoWhiteList: this.form.get('chunoWhiteList')!.value || undefined,
        tibedBatchSize: this.form.get('tibedBatchSize')!.value || undefined,
        embeddingsFile: this.form.get('embeddingsFile')!.value || undefined,
        tibedResetStore: this.form.get('tibedResetStore')!.value || false,
        prompt: this.form.get('prompt')!.value || null
      };
      this.save.emit(config);
    }
  }

  onClose(): void {
    this.close.emit();
  }
}
