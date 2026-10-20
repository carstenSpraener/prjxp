import { Component, Input, Output, EventEmitter, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { GlobalsService } from '../../services/globals.service';
import { ChatService } from '../../services/chat.service';
import { ChatSettingsPersistenceService } from '../../services/chat-settings-persistence.service';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { catchError, tap, of } from 'rxjs';

@Component({
  selector: 'chat-settings-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat-settings-dialog.component.html',
  styleUrl: './chat-settings-dialog.component.css'
})
export class ChatSettingsDialogComponent implements OnInit {
  @Input() currentModel = '';
  @Input() currentProjectContext: string | null = null;
  @Input() currentUseTools = false;

  @Output() close = new EventEmitter<{ model: string; projectContext: string | null; useTools: boolean }>();

  // Use signals for reactive template updates
  chatModels = signal<ChatModelReference[]>([]);
  availableProjects = signal<string[]>([]);
  selectedModel = signal('');
  selectedProject = signal('None');
  useTools = signal(false);

  serverTypeOptions = [
    { value: 'ollama', label: 'Ollama' },
    { value: 'openapi', label: 'OpenAI' },
    { value: 'lm-studio', label: 'LM Studio' },
    { value: 'gemini', label: 'Gemini' },
    { value: 'custom', label: 'Custom' }
  ];

  constructor(
    private globalsService: GlobalsService,
    private chatService: ChatService,
    private persistence: ChatSettingsPersistenceService
  ) {}

  ngOnInit(): void {
    // Initialize with current values from parent
    this.selectedModel.set(this.currentModel);
    this.useTools.set(this.currentUseTools);
    this.selectedProject.set(this.currentProjectContext ?? 'None');

    console.log('ChatSettingsDialog: init with', {
      model: this.currentModel, project: this.currentProjectContext, tools: this.currentUseTools
    });

    this.loadChatModels();
    this.loadProjects();
  }

  loadChatModels(): void {
    console.log('ChatSettingsDialog: loading chat models...');
    this.globalsService.getChatModels().pipe(
      tap(models => {
        console.log('ChatSettingsDialog: loaded', models.length, 'models');
        this.chatModels.set(models);

        // Auto-select first model if current is empty
        if (models.length > 0 && !this.selectedModel()) {
          this.selectedModel.set(models[0].stereoType ?? '');
        }
      }),
      catchError((err) => {
        console.error('ChatSettingsDialog: failed to load models', err);
        this.chatModels.set([]);
        return of();
      })
    ).subscribe();
  }

  loadProjects(): void {
    console.log('ChatSettingsDialog: loading projects...');
    this.chatService.getAvailableProjects().pipe(
      tap(projects => {
        console.log('ChatSettingsDialog: loaded', projects.length, 'projects:', projects);
        this.availableProjects.set(projects);

        // If current project context is not in the list, reset to None
        const current = this.selectedProject();
        if (current !== 'None' && !projects.includes(current)) {
          this.selectedProject.set('None');
        }
      }),
      catchError((err) => {
        console.error('ChatSettingsDialog: failed to load projects', err);
        this.availableProjects.set([]);
        return of();
      })
    ).subscribe();
  }

  onSave(): void {
    console.log('ChatSettingsDialog: onSave', {
      model: this.selectedModel(),
      project: this.selectedProject(),
      tools: this.useTools()
    });

    const projectContext = this.selectedProject() === 'None' ? null : this.selectedProject();
    this.persistence.save({
      model: this.selectedModel(),
      projectContext,
      useTools: this.useTools()
    });
    this.close.emit({
      model: this.selectedModel(),
      projectContext,
      useTools: this.useTools()
    });
  }

  onCancel(): void {
    console.log('ChatSettingsDialog: onCancel (restoring persisted values)');
    const persisted = this.persistence.load();
    const projectContext = persisted?.projectContext ?? this.currentProjectContext;
    this.close.emit({
      model: persisted?.model ?? this.currentModel,
      projectContext,
      useTools: persisted?.useTools ?? this.currentUseTools
    });
  }
}
