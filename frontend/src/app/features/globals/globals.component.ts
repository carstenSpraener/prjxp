import { Component, OnInit, OnDestroy, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { GlobalsService } from '../../services/globals.service';
import { SetupService } from '../../services/setup.service';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { EmbeddingConfig } from '../../models/embedding-config.model';
import { ChatModelCardComponent } from '../../components/chat-model-card/chat-model-card.component';
import { ChatModelEditorDialogComponent } from '../../components/chat-model-editor-dialog/chat-model-editor-dialog.component';
import { EmbeddingModelCardComponent } from '../../components/embedding-model-card/embedding-model-card.component';
import { EmbeddingModelEditorDialogComponent } from '../../components/embedding-model-editor-dialog/embedding-model-editor-dialog.component';
import { DialogOverlayComponent } from '../../components/dialog-overlay/dialog-overlay.component';
import { SetupStatusBannerComponent } from '../../components/setup-status-banner/setup-status-banner.component';
import { setupError } from '../setup/setup-validators';

@Component({selector: 'app-globals', standalone: true,
  imports: [CommonModule, FormsModule, ChatModelCardComponent, ChatModelEditorDialogComponent,
    EmbeddingModelCardComponent, EmbeddingModelEditorDialogComponent, DialogOverlayComponent, SetupStatusBannerComponent],
  templateUrl: './globals.component.html', styleUrl: './globals.component.css'})
export class GlobalsComponent implements OnInit, OnDestroy {
  chatModels = signal<ChatModelReference[]>([]);
  searchFilter = signal('');
  private _editingModel = signal<ChatModelReference | null | undefined>(undefined);
  isEditing = computed(() => this._editingModel() !== undefined);
  editingModel = computed(() => this._editingModel());
  filteredModels = signal<ChatModelReference[]>([]);
  embeddingModel = signal<EmbeddingConfig>({});
  private _editingEmbedding = signal(false);
  isEditingEmbedding = computed(() => this._editingEmbedding());
  saving = signal(false);
  editorError = signal<string | null>(null);
  chatLoaded = signal(false);
  embeddingLoaded = signal(false);
  chatLoading = signal(false);
  embeddingLoading = signal(false);
  editingVersion = '';
  embeddingEditingVersion = '';
  private subscriptions = new Subscription();
  constructor(private globalsService: GlobalsService, private setup: SetupService) {}
  ngOnInit(): void { this.loadModels(); this.loadEmbeddingModel(); }
  loadModels(): void {
    if (this.chatLoading()) return;
    this.chatLoading.set(true);
    this.subscriptions.add(this.globalsService.getChatModels().subscribe({
      next: models => {
        this.chatModels.set(models); this.applyFilter();
        this.chatLoaded.set(!!this.globalsService.chatVersion); this.chatLoading.set(false);
        if (!this.chatLoaded()) this.editorError.set(setupError({error: {code: 'PRECONDITION_REQUIRED'}}));
      },
      error: error => {this.chatLoading.set(false); this.editorError.set(setupError(error));}
    }));
  }
  onModelSaved(model: ChatModelReference): void {
    if (this.saving() || !this.isEditing() || !this.editingVersion) return;
    const current = this._editingModel();
    const request = current?.stereoType ? this.globalsService.updateChatModel(current.stereoType, model, this.editingVersion) :
      this.globalsService.createChatModel(model, this.editingVersion);
    this.saving.set(true); this.editorError.set(null);
    this.subscriptions.add(request.subscribe({
      next: () => {
        this.saving.set(false); this._editingModel.set(undefined);
        this.loadModels(); this.loadEmbeddingModel(); this.refreshStatus();
      },
      error: error => {this.saving.set(false); this.editorError.set(setupError(error));}
    }));
  }
  handleDelete(model: ChatModelReference): void {
    if (this.saving() || this.isEditing() || this.isEditingEmbedding() || !this.chatLoaded() || !model.stereoType ||
      !window.confirm(`Chat-Modell "${model.stereoType}" löschen?`)) return;
    this.saving.set(true); this.editorError.set(null);
    this.subscriptions.add(this.globalsService.deleteChatModel(model.stereoType, this.globalsService.chatVersion).subscribe({
      next: () => {this.saving.set(false); this.loadModels(); this.loadEmbeddingModel(); this.refreshStatus();},
      error: error => {this.saving.set(false); this.editorError.set(setupError(error));}
    }));
  }
  openEditor(model?: ChatModelReference): void {
    if (this.saving() || this.isEditingEmbedding() || !this.chatLoaded()) return;
    this.editingVersion = this.globalsService.chatVersion ?? '';
    this.editorError.set(null); this._editingModel.set(model ?? null);
  }
  closeEditor(): void { if (!this.saving()) this._editingModel.set(undefined); }
  onSearchChange(value: string): void { this.searchFilter.set(value); this.applyFilter(); }
  private applyFilter(): void {
    const filter = this.searchFilter().toLowerCase();
    this.filteredModels.set(this.chatModels().filter(model => !filter ||
      [model.stereoType, model.modelName, model.serverType, model.providerUrl]
        .some(value => value?.toLowerCase().includes(filter))));
  }
  loadEmbeddingModel(): void {
    if (this.embeddingLoading()) return;
    this.embeddingLoading.set(true);
    this.subscriptions.add(this.globalsService.getEmbeddingConfig().subscribe({
      next: model => {
        this.embeddingModel.set(model ?? {});
        this.embeddingLoaded.set(!!this.globalsService.embeddingVersion); this.embeddingLoading.set(false);
        if (!this.embeddingLoaded()) this.editorError.set(setupError({error: {code: 'PRECONDITION_REQUIRED'}}));
      },
      error: error => {this.embeddingLoading.set(false); this.editorError.set(setupError(error));}
    }));
  }
  onEmbeddingSaved(model: EmbeddingConfig, testId?: string | null, version = this.embeddingEditingVersion): void {
    if (this.saving() || !this.isEditingEmbedding() || !version) return;
    this.saving.set(true); this.editorError.set(null);
    this.subscriptions.add(this.globalsService.updateEmbeddingConfig(model, version, testId).subscribe({
      next: saved => {
        this.embeddingModel.set(saved); this.saving.set(false); this._editingEmbedding.set(false);
        this.loadModels(); this.refreshStatus();
      },
      error: error => {this.saving.set(false); this.editorError.set(setupError(error));}
    }));
  }
  openEmbeddingEditor(): void {
    if (this.saving() || this.isEditing() || !this.embeddingLoaded()) return;
    this.embeddingEditingVersion = this.globalsService.embeddingVersion ?? '';
    this.editorError.set(null); this._editingEmbedding.set(true);
  }
  closeEmbeddingEditor(): void { if (!this.saving()) this._editingEmbedding.set(false); }
  reloadConfiguration(): void {
    if (this.saving() || (this.isEditing() || this.isEditingEmbedding()) &&
      !window.confirm('Editorentwürfe verwerfen und gespeicherte Konfiguration neu laden?')) return;
    this._editingModel.set(undefined); this._editingEmbedding.set(false);
    this.editorError.set(null); this.loadModels(); this.loadEmbeddingModel(); this.refreshStatus();
  }
  private refreshStatus(): void { this.subscriptions.add(this.setup.getStatus().subscribe({error: () => {}})); }
  ngOnDestroy(): void { this.subscriptions.unsubscribe(); }
}
