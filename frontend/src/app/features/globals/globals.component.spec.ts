import { TestBed } from '@angular/core/testing';
import { GlobalsComponent } from './globals.component';
import { GlobalsService } from '../../services/globals.service';
import { ChatModelCardComponent } from '../../components/chat-model-card/chat-model-card.component';
import { ChatModelEditorDialogComponent } from '../../components/chat-model-editor-dialog/chat-model-editor-dialog.component';
import { EmbeddingModelCardComponent } from '../../components/embedding-model-card/embedding-model-card.component';
import { EmbeddingModelEditorDialogComponent } from '../../components/embedding-model-editor-dialog/embedding-model-editor-dialog.component';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { EmbeddingModelReference } from '../../models/embedding-model-reference.model';
import { of } from 'rxjs';
import { vi } from 'vitest';

describe('GlobalsComponent', () => {
  let component: GlobalsComponent;
  let fixture: any;
  let mockGlobalsService: any;

  const sampleModel: ChatModelReference = {
    stereoType: 'default',
    serverType: 'ollama',
    modelName: 'llama3',
    providerUrl: 'http://localhost:11434',
    timeoutSecs: 60,
    temperature: 0.7
  };

  const sampleEmbeddingModel: EmbeddingModelReference = {
    serverType: 'ollama',
    modelName: 'mxbai-embed-large',
    providerUrl: 'http://localhost:11434',
    timeoutSecs: 60
  };

  beforeEach(async () => {
    mockGlobalsService = {
      getChatModels: vi.fn().mockReturnValue(of([sampleModel])),
      createChatModel: vi.fn().mockReturnValue(of(sampleModel)),
      updateChatModel: vi.fn().mockReturnValue(of(sampleModel)),
      deleteChatModel: vi.fn().mockReturnValue(of()),
      getEmbeddingModel: vi.fn().mockReturnValue(of(sampleEmbeddingModel)),
      updateEmbeddingModel: vi.fn().mockReturnValue(of(sampleEmbeddingModel))
    };

    await TestBed.configureTestingModule({
      imports: [GlobalsComponent],
      providers: [
        { provide: GlobalsService, useValue: mockGlobalsService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(GlobalsComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('ngOnInit should load models', () => {
    component.ngOnInit();
    expect(mockGlobalsService.getChatModels).toHaveBeenCalled();
  });

  it('should have correct initial state', () => {
    expect(component.chatModels()).toEqual([]);
    expect(component.searchFilter()).toBe('');
    expect(component.isEditing()).toBe(false);
  });

  it('openEditor without model should set editing to new mode', () => {
    component.openEditor();
    expect(component.isEditing()).toBe(true);
    expect(component.editingModel()).toBeNull();
  });

  it('openEditor with model should set editing to edit mode', () => {
    component.openEditor(sampleModel);
    expect(component.isEditing()).toBe(true);
    expect(component.editingModel()?.stereoType).toBe('default');
  });

  it('closeEditor should stop editing', () => {
    component.openEditor(sampleModel);
    component.closeEditor();
    expect(component.isEditing()).toBe(false);
  });

  it('onSearchChange should filter models', () => {
    component.chatModels.set([sampleModel]);
    component.onSearchChange('llama');
    expect(component.filteredModels()).toHaveLength(1);

    component.onSearchChange('nonexistent');
    expect(component.filteredModels()).toHaveLength(0);
  });

  it('should render search input', () => {
    fixture.detectChanges();
    const searchInput = fixture.nativeElement.querySelector('.search-input');
    expect(searchInput).toBeTruthy();
  });

  it('should render add model button', () => {
    fixture.detectChanges();
    const addButton = fixture.nativeElement.querySelector('.btn-add');
    expect(addButton).toBeTruthy();
    expect(addButton.textContent).toContain('Add Model');
  });

  it('should show empty state when no models', () => {
    mockGlobalsService.getChatModels.mockReturnValue(of([]));
    component.ngOnInit();
    fixture.detectChanges();

    const emptyState = fixture.nativeElement.querySelector('.empty-state');
    expect(emptyState).toBeTruthy();
  });

  it('should render chat model cards', () => {
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('chat-model-card');
    expect(cards.length).toBe(1);
  });

  it('handleDelete should call service and reload', () => {
    component.handleDelete(sampleModel);
    expect(mockGlobalsService.deleteChatModel).toHaveBeenCalledWith('default');
  });

  it('onModelSaved with existing model should call update', () => {
    component.openEditor(sampleModel);
    component.onModelSaved(sampleModel);
    expect(mockGlobalsService.updateChatModel).toHaveBeenCalledWith('default', sampleModel);
  });

  it('onModelSaved with new model should call create', () => {
    component.openEditor(); // null = new
    component.onModelSaved(sampleModel);
    expect(mockGlobalsService.createChatModel).toHaveBeenCalledWith(sampleModel);
  });

  it('should have globals title with neon glow', () => {
    fixture.detectChanges();
    const title = fixture.nativeElement.querySelector('h1');
    expect(title).toBeTruthy();
    expect(title.textContent).toContain('Globals');
    expect(title.classList).toContain('neon-glow');
  });

  // Embedding Model tests
  it('ngOnInit should load embedding model', () => {
    component.ngOnInit();
    expect(mockGlobalsService.getEmbeddingModel).toHaveBeenCalled();
  });

  it('should have correct initial embedding model state', () => {
    expect(component.embeddingModel()).toEqual({});
    expect(component.isEditingEmbedding()).toBe(false);
  });

  it('openEmbeddingEditor should set editing to true', () => {
    component.openEmbeddingEditor();
    expect(component.isEditingEmbedding()).toBe(true);
  });

  it('closeEmbeddingEditor should set editing to false', () => {
    component.openEmbeddingEditor();
    component.closeEmbeddingEditor();
    expect(component.isEditingEmbedding()).toBe(false);
  });

  it('onEmbeddingSaved should call update service', () => {
    component.onEmbeddingSaved(sampleEmbeddingModel);
    expect(mockGlobalsService.updateEmbeddingModel).toHaveBeenCalledWith(sampleEmbeddingModel);
  });

  it('should render embedding model card', () => {
    fixture.detectChanges();
    const cards = fixture.nativeElement.querySelectorAll('embedding-model-card');
    expect(cards.length).toBe(1);
  });

  it('should render embedding model section header', () => {
    fixture.detectChanges();
    const headers = fixture.nativeElement.querySelectorAll('.section-header h2');
    expect(headers.length).toBe(2); // Chat Models + Embedding Model
    expect(headers[1].textContent).toContain('Embedding Model');
  });

  it('should render edit configuration button for embedding model', () => {
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('.btn-add');
    expect(buttons.length).toBe(2); // Add Model + Edit Configuration
    expect(buttons[1].textContent).toContain('Edit Configuration');
  });

  it('should show embedding editor dialog when editing', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('embedding-model-editor-dialog')).toBeFalsy();

    component.openEmbeddingEditor();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('embedding-model-editor-dialog')).toBeTruthy();
  });

  it('should hide embedding editor dialog when closed', () => {
    component.openEmbeddingEditor();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('embedding-model-editor-dialog')).toBeTruthy();

    component.closeEmbeddingEditor();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('embedding-model-editor-dialog')).toBeFalsy();
  });
});
