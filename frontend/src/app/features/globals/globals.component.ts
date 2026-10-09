import { Component, OnInit, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { GlobalsService } from '../../services/globals.service';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { EmbeddingModelReference } from '../../models/embedding-model-reference.model';
import { ChatModelCardComponent } from '../../components/chat-model-card/chat-model-card.component';
import { ChatModelEditorDialogComponent } from '../../components/chat-model-editor-dialog/chat-model-editor-dialog.component';
import { EmbeddingModelCardComponent } from '../../components/embedding-model-card/embedding-model-card.component';
import { EmbeddingModelEditorDialogComponent } from '../../components/embedding-model-editor-dialog/embedding-model-editor-dialog.component';
import { catchError, tap, of } from 'rxjs';

@Component({
  selector: 'app-globals',
  standalone: true,
  imports: [CommonModule, FormsModule, ChatModelCardComponent, ChatModelEditorDialogComponent, EmbeddingModelCardComponent, EmbeddingModelEditorDialogComponent],
  templateUrl: './globals.component.html',
  styleUrl: './globals.component.css'
})
export class GlobalsComponent implements OnInit {
  chatModels = signal<ChatModelReference[]>([]);
  searchFilter = signal<string>('');
  // null = new model, object = edit existing, undefined = not editing
  private _editingModel = signal<ChatModelReference | null | undefined>(undefined);

  isEditing = computed(() => this._editingModel() !== undefined);
  editingModel = computed(() => this._editingModel());

  filteredModels = signal<ChatModelReference[]>([]);

  // Embedding Model state
  embeddingModel = signal<EmbeddingModelReference>({});
  private _editingEmbedding = signal<boolean>(false);
  isEditingEmbedding = computed(() => this._editingEmbedding());

  constructor(private globalsService: GlobalsService) {}

  ngOnInit(): void {
    this.loadModels();
    this.loadEmbeddingModel();
  }

  loadModels(): void {
    this.globalsService.getChatModels().pipe(
      tap(models => {
        this.chatModels.set(models);
        this.applyFilter();
      }),
      catchError(() => {
        this.chatModels.set([]);
        this.filteredModels.set([]);
        return of();
      })
    ).subscribe();
  }

  onModelSaved(model: ChatModelReference): void {
    const current = this._editingModel();
    if (current !== null && current?.stereoType) {
      // Update existing
      this.globalsService.updateChatModel(current.stereoType, model).pipe(
        tap(() => {
          this._editingModel.set(undefined);
          this.loadModels();
        }),
        catchError(() => of())
      ).subscribe();
    } else {
      // Create new
      this.globalsService.createChatModel(model).pipe(
        tap(() => {
          this._editingModel.set(undefined);
          this.loadModels();
        }),
        catchError(() => of())
      ).subscribe();
    }
  }

  handleDelete(model: ChatModelReference): void {
    console.log('GlobalsComponent: handleDelete() called for', model.stereoType);
    if (model.stereoType) {
      this.globalsService.deleteChatModel(model.stereoType).subscribe({
        next: () => {
          console.log('GlobalsComponent: delete succeeded, reloading...');
          this.loadModels();
        },
        error: (err) => {
          console.error('GlobalsComponent: delete failed', err);
          alert(`Fehler beim Löschen: ${err.error || err.message || err}`);
        }
      });
    } else {
      console.warn('GlobalsComponent: handleDelete() called but stereoType is null/empty');
    }
  }

  openEditor(model?: ChatModelReference): void {
    this._editingModel.set(model ?? null);
  }

  closeEditor(): void {
    this._editingModel.set(undefined);
  }

  onSearchChange(value: string): void {
    this.searchFilter.set(value);
    this.applyFilter();
  }

  private applyFilter(): void {
    const filter = this.searchFilter().toLowerCase();
    if (!filter) {
      this.filteredModels.set(this.chatModels());
    } else {
      const filtered = this.chatModels().filter(m =>
        (m.stereoType ?? '').toLowerCase().includes(filter) ||
        (m.modelName ?? '').toLowerCase().includes(filter) ||
        (m.serverType ?? '').toLowerCase().includes(filter) ||
        (m.providerUrl ?? '').toLowerCase().includes(filter)
      );
      this.filteredModels.set(filtered);
    }
  }

  // Embedding Model methods
  loadEmbeddingModel(): void {
    this.globalsService.getEmbeddingModel().pipe(
      tap(model => this.embeddingModel.set(model)),
      catchError(() => of())
    ).subscribe();
  }

  onEmbeddingSaved(model: EmbeddingModelReference): void {
    this.globalsService.updateEmbeddingModel(model).pipe(
      tap(saved => this.embeddingModel.set(saved)),
      catchError(() => of())
    ).subscribe();
  }

  openEmbeddingEditor(): void {
    this._editingEmbedding.set(true);
  }

  closeEmbeddingEditor(): void {
    this._editingEmbedding.set(false);
  }
}
