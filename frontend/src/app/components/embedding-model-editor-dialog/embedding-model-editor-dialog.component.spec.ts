import { TestBed } from '@angular/core/testing';
import { EmbeddingModelEditorDialogComponent } from './embedding-model-editor-dialog.component';
import { ReactiveFormsModule } from '@angular/forms';
import { EmbeddingModelReference } from '../../models/embedding-model-reference.model';
import { GlobalsService } from '../../services/globals.service';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';

const mockGlobalsService = {
  testChatModel: vi.fn(), testEmbeddingModel: vi.fn(),
  listChatModels: vi.fn(), listEmbeddingModels: vi.fn()
};

describe('EmbeddingModelEditorDialogComponent', () => {
  let component: EmbeddingModelEditorDialogComponent;
  let fixture: any;

  const sampleModel: EmbeddingModelReference = {
    serverType: 'ollama',
    modelName: 'mxbai-embed-large',
    providerUrl: 'http://localhost:11434',
    timeoutSecs: 60
  };

  beforeEach(async () => {
    vi.clearAllMocks();

    await TestBed.configureTestingModule({
      imports: [EmbeddingModelEditorDialogComponent, ReactiveFormsModule],
      providers: [{ provide: GlobalsService, useValue: mockGlobalsService }]
    }).compileComponents();

    fixture = TestBed.createComponent(EmbeddingModelEditorDialogComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize form with defaults when no model provided', () => {
    component.model = null;
    fixture.detectChanges();

    expect(component.form).toBeTruthy();
    expect(component.form.get('serverType')?.value).toBe('ollama');
    expect(component.form.get('modelName')?.value).toBe('');
    expect(component.form.get('providerUrl')?.value).toBe('');
    expect(component.form.get('apiKey')?.value).toBe('');
    expect(component.form.get('timeoutSecs')?.value).toBe(60);
  });

  it('should populate form with existing model data', () => {
    component.model = sampleModel;
    component.ngOnInit(); // re-initialize form with new model data
    fixture.detectChanges();

    expect(component.form.get('serverType')?.value).toBe('ollama');
    expect(component.form.get('modelName')?.value).toBe('mxbai-embed-large');
    expect(component.form.get('providerUrl')?.value).toBe('http://localhost:11434');
    expect(component.form.get('timeoutSecs')?.value).toBe(60);
  });

  it('should not show API key field for ollama', () => {
    component.model = null;
    fixture.detectChanges();
    expect(component.showApiKey()).toBe(false);
  });

  it('should show API key field for openai', () => {
    component.model = null;
    fixture.detectChanges();
    component.form.get('serverType')?.setValue('openai');
    expect(component.showApiKey()).toBe(true);
  });

  it('should show API key field for gemini', () => {
    component.model = null;
    fixture.detectChanges();
    component.form.get('serverType')?.setValue('gemini');
    expect(component.showApiKey()).toBe(true);
  });

  it('should show API key field for custom', () => {
    component.model = null;
    fixture.detectChanges();
    component.form.get('serverType')?.setValue('custom');
    expect(component.showApiKey()).toBe(true);
  });

  it('should not show API key field for lm-studio', () => {
    component.model = null;
    fixture.detectChanges();
    component.form.get('serverType')?.setValue('lm-studio');
    expect(component.showApiKey()).toBe(false);
  });

  it('should emit save event with correct data on valid form submission', () => {
    component.model = null;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({
      serverType: 'ollama',
      modelName: 'nomic-embed-text',
      providerUrl: 'http://localhost:11434'
    });

    component.onSave();

    expect(saveSpy).toHaveBeenCalled();
    const savedModel = saveSpy.mock.calls[0][0];
    expect(savedModel.serverType).toBe('ollama');
    expect(savedModel.modelName).toBe('nomic-embed-text');
    expect(savedModel.providerUrl).toBe('http://localhost:11434');
  });

  it('should not emit save event when form is invalid', () => {
    component.model = null;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    // Leave modelName empty (required)
    component.onSave();

    expect(saveSpy).not.toHaveBeenCalled();
  });

  it('should emit close event on onClose', () => {
    const closeSpy = vi.fn();
    component.close.subscribe(closeSpy);

    component.onClose();
    expect(closeSpy).toHaveBeenCalled();
  });

  it('should have correct server type options', () => {
    expect(component.serverTypeOptions).toHaveLength(5);
    expect(component.serverTypeOptions[0].value).toBe('ollama');
    expect(component.serverTypeOptions[1].value).toBe('openai');
    expect(component.serverTypeOptions[2].value).toBe('lm-studio');
    expect(component.serverTypeOptions[3].value).toBe('gemini');
    expect(component.serverTypeOptions[4].value).toBe('custom');
  });

  it('should render dialog header with correct title', () => {
    component.model = null;
    fixture.detectChanges();

    const header = fixture.nativeElement.querySelector('.dialog-header h2');
    expect(header.textContent).toBe('Edit Embedding Model');
  });

  it('should have save and cancel buttons', () => {
    component.model = null;
    fixture.detectChanges();

    const saveBtn = fixture.nativeElement.querySelector('.btn-save');
    const cancelBtn = fixture.nativeElement.querySelector('.btn-cancel');
    expect(saveBtn).toBeTruthy();
    expect(cancelBtn).toBeTruthy();
  });

  it('should validate timeout minimum value', () => {
    component.model = null;
    fixture.detectChanges();

    component.form.get('timeoutSecs')?.setValue(5);
    expect(component.form.get('timeoutSecs')?.invalid).toBe(true);

    component.form.get('timeoutSecs')?.setValue(10);
    expect(component.form.get('timeoutSecs')?.valid).toBe(true);
  });

  it('should validate provider URL pattern', () => {
    component.model = null;
    fixture.detectChanges();

    component.form.get('providerUrl')?.setValue('not-a-url');
    expect(component.form.get('providerUrl')?.invalid).toBe(true);

    component.form.get('providerUrl')?.setValue('http://localhost:11434');
    expect(component.form.get('providerUrl')?.valid).toBe(true);

    component.form.get('providerUrl')?.setValue('https://api.openai.com');
    expect(component.form.get('providerUrl')?.valid).toBe(true);

    component.form.get('providerUrl')?.setValue('${EMBEDDING_URL:http://localhost:1234/v1}');
    expect(component.form.get('providerUrl')?.valid).toBe(true);
  });

  it('should validate model name is required', () => {
    component.model = null;
    fixture.detectChanges();

    component.form.get('modelName')?.setValue('');
    expect(component.form.get('modelName')?.invalid).toBe(true);

    component.form.get('modelName')?.setValue('some-model');
    expect(component.form.get('modelName')?.valid).toBe(true);
  });

  it('should set apiKey to null when empty on save', () => {
    component.model = null;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({
      modelName: 'test-model',
      providerUrl: 'http://localhost:11434'
    });

    component.onSave();

    const savedModel = saveSpy.mock.calls[0][0];
    expect(savedModel.apiKey).toBeNull();
  });

  it('should preserve apiKey when provided on save', () => {
    component.model = null;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({
      serverType: 'openai',
      modelName: 'test-model',
      providerUrl: 'https://api.openai.com',
      apiKey: 'sk-123456'
    });

    component.onSave();

    const savedModel = saveSpy.mock.calls[0][0];
    expect(savedModel.apiKey).toBe('sk-123456');
  });

  it('should preserve existing apiKey when masked value is submitted in edit mode', () => {
    component.model = { ...sampleModel, apiKey: '********' };
    component.ngOnInit();
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({ apiKey: '********' });
    component.onSave();

    const savedModel = saveSpy.mock.calls[0][0];
    expect(savedModel.apiKey).toBe('********');
  });

  it('onTest should call testEmbeddingModel and set testResult on success', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ serverType: 'ollama', modelName: 'mxbai-embed-large', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.testEmbeddingModel.mockReturnValue(of({ success: true, message: 'ok', latencyMs: 17, response: null, dimensions: 1024 }));
    component.onTest();
    expect(mockGlobalsService.testEmbeddingModel).toHaveBeenCalled();
    expect(component.testResult?.success).toBe(true);
    expect(component.testResult?.dimensions).toBe(1024);
    expect(component.testLoading).toBe(false);
  });

  it('onTest should set a failure result on error', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ serverType: 'ollama', modelName: 'mxbai-embed-large', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.testEmbeddingModel.mockReturnValue(throwError(() => new Error('boom')));
    component.onTest();
    expect(component.testResult?.success).toBe(false);
  });

  it('onReadModels should populate availableModels', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ serverType: 'ollama', modelName: '', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.listEmbeddingModels.mockReturnValue(of(['mxbai-embed-large', 'nomic-embed-text']));
    component.onReadModels();
    expect(component.availableModels).toEqual(['mxbai-embed-large', 'nomic-embed-text']);
  });

  it('onReadModels should set listError on error', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ serverType: 'custom', modelName: '', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.listEmbeddingModels.mockReturnValue(throwError(() => new Error('not supported')));
    component.onReadModels();
    expect(component.listError).toBeTruthy();
  });

  it('should render datalist options for availableModels', () => {
    // availableModels must be set before the FIRST detectChanges(): in this
    // test environment a subsequent detectChanges() is a no-op, so the @for
    // would never render the options.
    component.model = null;
    component.availableModels = ['mxbai-embed-large', 'nomic-embed-text'];
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('#embedding-model-names option').length).toBe(2);
  });
});
