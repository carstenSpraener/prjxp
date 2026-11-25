import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import { GlobalsComponent } from './globals.component';
import { GlobalsService } from '../../services/globals.service';
import { SetupService } from '../../services/setup.service';
import { EmbeddingModelEditorDialogComponent } from '../../components/embedding-model-editor-dialog/embedding-model-editor-dialog.component';
import { setupConfiguration } from '../setup/setup-fixtures';

describe('Globals editors concurrency', () => {
  const cfg = setupConfiguration();
  let api: Record<'getChatModels' | 'getEmbeddingConfig' | 'getServerTypes' | 'updateEmbeddingConfig' |
    'createChatModel' | 'updateChatModel' | 'deleteChatModel' | 'testEmbeddingConfig' | 'listEmbeddingModels',
    ReturnType<typeof vi.fn>> & {chatVersion: string; embeddingVersion: string};
  beforeEach(() => {
    api = {getChatModels: vi.fn(() => of([])), getEmbeddingConfig: vi.fn(() => of(cfg.embedding)),
      getServerTypes: vi.fn(() => of(['ollama'])), updateEmbeddingConfig: vi.fn(() => of(cfg.embedding)),
      createChatModel: vi.fn(() => of({})), updateChatModel: vi.fn(() => of({})),
      deleteChatModel: vi.fn(() => of(undefined)), testEmbeddingConfig: vi.fn(() => of({
        success: true, dimensions: 3, testId: 'receipt', configVersion: 'v2', latencyMs: 1, message: 'OK', response: null
      })), listEmbeddingModels: vi.fn(() => of(['embed'])), chatVersion: 'v1', embeddingVersion: 'v1'};
    TestBed.configureTestingModule({imports: [GlobalsComponent, EmbeddingModelEditorDialogComponent], providers: [
      provideRouter([]), {provide: GlobalsService, useValue: api},
      {provide: SetupService, useValue: {status: signal(cfg.status), error: signal(null),
        loading: signal(false), getStatus: vi.fn(() => of(cfg.status))}}
    ]});
    vi.spyOn(window, 'confirm').mockReturnValue(false);
  });
  it('embedding save failure retains editor and draft and confirmed reload is explicit', () => {
    const fixture = TestBed.createComponent(GlobalsComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.openEmbeddingEditor();
    api.updateEmbeddingConfig.mockReturnValueOnce(throwError(() => ({error: {code: 'CONFIG_CHANGED', message: 'private'}})));
    c.onEmbeddingSaved({...cfg.embedding, modelName: 'new'});
    expect(c.isEditingEmbedding()).toBe(true);
    expect(c.editorError()).toContain('CONFIG_CHANGED');
    expect(c.editorError()).not.toContain('private');
    c.reloadConfiguration();
    expect(c.isEditingEmbedding()).toBe(true);
    vi.mocked(window.confirm).mockReturnValue(true);
    c.reloadConfiguration();
    expect(c.isEditingEmbedding()).toBe(false);
    fixture.destroy();
  });
  it('CRUD uses editing snapshot and dialogs stay until asynchronous success', () => {
    const fixture = TestBed.createComponent(GlobalsComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const pending = new Subject<object>();
    api.createChatModel.mockReturnValue(pending);
    c.openEditor(); c.onModelSaved({stereoType: 'new'});
    c.onModelSaved({stereoType: 'new'}); c.closeEditor();
    expect(api.createChatModel).toHaveBeenCalledOnce();
    expect(api.createChatModel).toHaveBeenCalledWith({stereoType: 'new'}, 'v1');
    expect(c.isEditing()).toBe(true);
    pending.next({}); pending.complete();
    expect(c.isEditing()).toBe(false);
    c.openEditor({stereoType: 'default'});
    api.updateChatModel.mockReturnValueOnce(throwError(() => ({})));
    c.onModelSaved({stereoType: 'default'});
    expect(c.isEditing()).toBe(true);
    vi.mocked(window.confirm).mockReturnValue(true);
    api.deleteChatModel.mockReturnValueOnce(throwError(() => ({})));
    c.handleDelete({stereoType: 'default'});
    expect(c.editorError()).toBeTruthy();
    fixture.destroy();
  });
  it('embedding editor shares DTO, preserves read-model datalist and drops edited/late receipts', () => {
    const fixture = TestBed.createComponent(EmbeddingModelEditorDialogComponent);
    fixture.componentRef.setInput('model', cfg.embedding);
    fixture.componentRef.setInput('configVersion', 'v1');
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.onReadModels();
    expect(api.listEmbeddingModels).toHaveBeenCalledWith({serverType: 'ollama', providerUrl: 'http://localhost:11434', apiKey: ''});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('datalist option').value).toBe('embed');
    c.onTest();
    expect(c.testId).toBe('receipt');
    const save = vi.spyOn(c.save, 'emit');
    c.onSave();
    expect(save).toHaveBeenCalledWith(expect.objectContaining({type: 'OLLAMA', apiBaseURL: 'http://localhost:11434'}));
    c.form.controls.modelName.setValue('edited');
    expect(c.testId).toBeNull();
    c.form.controls.dimensions.setValue(null);
    expect(c.configuration().resetDimensions).toBe(true);
    const pending = new Subject<unknown>();
    api.testEmbeddingConfig.mockReturnValue(pending);
    c.onTest();
    c.form.controls.modelName.setValue('later');
    pending.next({success: true, dimensions: 3, testId: 'stale', configVersion: 'v3'});
    pending.complete();
    expect(c.testId).toBeNull();
    fixture.destroy();
  });
});
