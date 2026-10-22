import { TestBed } from '@angular/core/testing';
import { ChatModelEditorDialogComponent } from './chat-model-editor-dialog.component';
import { ReactiveFormsModule } from '@angular/forms';
import { ChatModelReference } from '../../models/chat-model-reference.model';
import { GlobalsService } from '../../services/globals.service';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';

const mockGlobalsService = {
  testChatModel: vi.fn(), testEmbeddingModel: vi.fn(),
  listChatModels: vi.fn(), listEmbeddingModels: vi.fn()
};

describe('ChatModelEditorDialogComponent', () => {
  let component: ChatModelEditorDialogComponent;
  let fixture: any;

  const sampleModel: ChatModelReference = {
    stereoType: 'default',
    serverType: 'ollama',
    modelName: 'llama3',
    providerUrl: 'http://localhost:11434',
    timeoutSecs: 60,
    temperature: 0.7
  };

  beforeEach(async () => {
    vi.clearAllMocks();

    await TestBed.configureTestingModule({
      imports: [ChatModelEditorDialogComponent],
      providers: [{ provide: GlobalsService, useValue: mockGlobalsService }]
    }).compileComponents();

    fixture = TestBed.createComponent(ChatModelEditorDialogComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize form with defaults when no model provided', () => {
    component.model = null;
    fixture.detectChanges();

    expect(component.form).toBeTruthy();
    expect(component.form.get('stereoType')?.value).toBe('');
    expect(component.form.get('serverType')?.value).toBe('ollama');
    expect(component.form.get('modelName')?.value).toBe('');
    expect(component.form.get('providerUrl')?.value).toBe('');
    expect(component.form.get('timeoutSecs')?.value).toBe(60);
    expect(component.form.get('temperature')?.value).toBe(0.7);
  });

  it('should populate form with existing model data', () => {
    component.model = sampleModel;
    fixture.detectChanges();

    expect(component.form.get('stereoType')?.value).toBe('default');
    expect(component.form.get('serverType')?.value).toBe('ollama');
    expect(component.form.get('modelName')?.value).toBe('llama3');
    expect(component.form.get('providerUrl')?.value).toBe('http://localhost:11434');
  });

  it('should set isEditing to true when editing existing model', () => {
    component.model = sampleModel;
    fixture.detectChanges();
    expect(component.isEditing).toBe(true);
  });

  it('should set isEditing to false when creating new model', () => {
    component.model = null;
    fixture.detectChanges();
    expect(component.isEditing).toBe(false);
  });

  it('should not show API key field for ollama', () => {
    component.model = null;
    fixture.detectChanges();
    expect(component.showApiKey()).toBe(false);
  });

  it('should show API key field for openapi', () => {
    component.model = null;
    fixture.detectChanges();
    component.form.get('serverType')?.setValue('openapi');
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

  it('should not show extra args for non-custom server types', () => {
    component.model = null;
    fixture.detectChanges();
    expect(component.showExtraArgs()).toBe(false);

    component.form.get('serverType')?.setValue('openapi');
    expect(component.showExtraArgs()).toBe(false);
  });

  it('should show extra args for custom server type', () => {
    component.model = null;
    fixture.detectChanges();
    component.form.get('serverType')?.setValue('custom');
    expect(component.showExtraArgs()).toBe(true);
  });

  it('should add extra arg to form array', () => {
    component.model = null;
    fixture.detectChanges();

    expect(component.extraArgsArray.length).toBe(0);
    component.addExtraArg('key1', 'value1');
    expect(component.extraArgsArray.length).toBe(1);
  });

  it('should remove extra arg from form array', () => {
    component.model = null;
    fixture.detectChanges();

    component.addExtraArg('key1', 'value1');
    component.addExtraArg('key2', 'value2');
    expect(component.extraArgsArray.length).toBe(2);

    component.removeExtraArg(0);
    expect(component.extraArgsArray.length).toBe(1);
  });

  it('should emit save event with correct data on valid form submission', () => {
    component.model = null;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({
      stereoType: 'test',
      serverType: 'ollama',
      modelName: 'llama3.1',
      providerUrl: 'http://localhost:11434'
    });

    component.onSave();

    expect(saveSpy).toHaveBeenCalled();
      const savedModel = saveSpy.mock.calls[0][0];
    expect(savedModel.stereoType).toBe('test');
    expect(savedModel.modelName).toBe('llama3.1');
  });

  it('should not emit save event when form is invalid', () => {
    component.model = null;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    // Leave stereoType empty (required)
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
    expect(component.serverTypeOptions[1].value).toBe('openapi');
    expect(component.serverTypeOptions[2].value).toBe('lm-studio');
    expect(component.serverTypeOptions[3].value).toBe('gemini');
    expect(component.serverTypeOptions[4].value).toBe('custom');
  });

  it('should render dialog header with correct title for new model', () => {
    component.model = null;
    fixture.detectChanges();

    const header = fixture.nativeElement.querySelector('.dialog-header h2');
    expect(header.textContent).toBe('Add New Model');
  });

  it('should render dialog header with correct title for editing', () => {
    component.model = sampleModel;
    fixture.detectChanges();

    const header = fixture.nativeElement.querySelector('.dialog-header h2');
    expect(header.textContent).toBe('Edit Model');
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

  it('should validate temperature range', () => {
    component.model = null;
    fixture.detectChanges();

    component.form.get('temperature')?.setValue(-0.1);
    expect(component.form.get('temperature')?.invalid).toBe(true);

    component.form.get('temperature')?.setValue(2.1);
    expect(component.form.get('temperature')?.invalid).toBe(true);

    component.form.get('temperature')?.setValue(1.0);
    expect(component.form.get('temperature')?.valid).toBe(true);
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

    component.form.get('providerUrl')?.setValue('${OPENAI_BASE_URL:http://localhost:1234/v1}');
    expect(component.form.get('providerUrl')?.valid).toBe(true);
  });

  it('should preserve existing apiKey when masked value is submitted in edit mode', () => {
    component.model = { ...sampleModel, apiKey: '********' };
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({ apiKey: '********' });
    component.onSave();

    const savedModel = saveSpy.mock.calls[0][0];
    expect(savedModel.apiKey).toBe('********');
  });

  it('should load extra args from existing model', () => {
    const modelWithArgs: ChatModelReference = {
      ...sampleModel,
      serverType: 'custom',
      args: { header1: 'value1', header2: 'value2' }
    };
    component.model = modelWithArgs;
    fixture.detectChanges();

    expect(component.extraArgsArray.length).toBe(2);
  });

  it('should prefill prompt from model', () => {
    component.model = { ...sampleModel, prompt: 'Be concise.' };
    fixture.detectChanges();

    expect(component.form.get('prompt')?.value).toBe('Be concise.');
  });

  it('onSave() should emit prompt', () => {
    component.model = sampleModel;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.form.patchValue({ prompt: 'Be concise.' });
    component.onSave();

    const emitted = saveSpy.mock.calls[0][0];
    expect(emitted.prompt).toBe('Be concise.');
  });

  it('onSave() should emit null for empty prompt', () => {
    component.model = sampleModel;
    fixture.detectChanges();

    const saveSpy = vi.fn();
    component.save.subscribe(saveSpy);

    component.onSave();

    const emitted = saveSpy.mock.calls[0][0];
    expect(emitted.prompt).toBeNull();
  });

  it('onTest should call testChatModel and set testResult on success', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ stereoType: 'default', serverType: 'ollama', modelName: 'llama3', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.testChatModel.mockReturnValue(of({ success: true, message: 'ok', latencyMs: 42, response: 'Hello!', dimensions: null }));
    component.onTest();
    expect(mockGlobalsService.testChatModel).toHaveBeenCalled();
    expect(component.testResult?.success).toBe(true);
    expect(component.testResult?.response).toBe('Hello!');
    expect(component.testLoading).toBe(false);
  });

  it('onTest should set a failure result on error', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ stereoType: 'default', serverType: 'ollama', modelName: 'llama3', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.testChatModel.mockReturnValue(throwError(() => new Error('boom')));
    component.onTest();
    expect(component.testResult?.success).toBe(false);
  });

  it('onReadModels should populate availableModels', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ stereoType: 'default', serverType: 'ollama', modelName: '', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.listChatModels.mockReturnValue(of(['llama3', 'gemma']));
    component.onReadModels();
    expect(component.availableModels).toEqual(['llama3', 'gemma']);
  });

  it('onReadModels should set listError on error', () => {
    component.model = null; fixture.detectChanges();
    component.form.patchValue({ stereoType: 'default', serverType: 'custom', modelName: '', providerUrl: 'http://localhost:11434' });
    mockGlobalsService.listChatModels.mockReturnValue(throwError(() => new Error('not supported')));
    component.onReadModels();
    expect(component.listError).toBeTruthy();
  });

  it('should render datalist options for availableModels', () => {
    // availableModels must be set before the FIRST detectChanges(): in this
    // test environment a subsequent detectChanges() is a no-op, so the @for
    // would never render the options.
    component.model = null;
    component.availableModels = ['llama3', 'gemma'];
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('datalist option').length).toBe(2);
  });
});
