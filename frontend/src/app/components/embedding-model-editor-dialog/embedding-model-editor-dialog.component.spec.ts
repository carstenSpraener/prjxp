import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, Subject, throwError } from 'rxjs';
import { EmbeddingModelEditorDialogComponent } from './embedding-model-editor-dialog.component';
import { GlobalsService } from '../../services/globals.service';
import { SetupService } from '../../services/setup.service';
import { setupConfiguration } from '../../features/setup/setup-fixtures';
describe('EmbeddingModelEditorDialogComponent', () => {
  const api = {testEmbeddingConfig: vi.fn(), listEmbeddingModels: vi.fn()};
  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({imports: [EmbeddingModelEditorDialogComponent], providers: [
      {provide: GlobalsService, useValue: api},
      {provide: SetupService, useValue: {getStatus: () => of(setupConfiguration().status)}}
    ]});
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });
  function create(existing = true) {
    const fixture = TestBed.createComponent(EmbeddingModelEditorDialogComponent);
    fixture.componentRef.setInput('model', existing ? setupConfiguration().embedding : null);
    fixture.componentRef.setInput('configVersion', 'v1');
    fixture.detectChanges();
    return {fixture, c: fixture.componentInstance};
  }
  it('prefills saved values ahead of defaults and emits shared DTO on valid save only', () => {
    const {fixture, c} = create();
    const save = vi.spyOn(c.save, 'emit');
    c.onSave();
    expect(save).toHaveBeenCalledWith(setupConfiguration().embedding);
    c.form.controls.timeout.setValue(1.5);
    c.onSave();
    expect(save).toHaveBeenCalledOnce();
    fixture.componentRef.setInput('model', {...setupConfiguration().embedding, timeout: 7});
    fixture.detectChanges();
    expect(c.form.controls.timeout.value).toBe(7);
    c.form.controls.type.setValue('OPEN_AI');
    expect(c.showApiKey()).toBe(true);
    fixture.destroy();
  });
  it('new defaults and secret/ref/clear/null stay typed; explicit dimension reset', () => {
    const {fixture, c} = create(false);
    expect(c.form.controls.timeout.value).toBe(20);
    expect(c.form.controls.maxSegmentsPerBatch.value).toBe(32);
    expect(c.showApiKey()).toBe(false);
    expect(c.form.invalid).toBe(true);
    for (const apiKey of ['********', '${KEY}', '', null]) {
      c.form.controls.apiKey.setValue(apiKey);
      expect(c.configuration().apiKey).toBe(apiKey);
    }
    expect(fixture.nativeElement.querySelector('input[type=password]').autocomplete).toBe('new-password');
    fixture.destroy();
  });
  it('test and list failures are redacted and keep editor available for retry', () => {
    const {fixture, c} = create();
    api.testEmbeddingConfig.mockReturnValue(throwError(() => ({error: {message: 'SECRET'}})));
    c.onTest();
    expect(c.testResult?.success).toBe(false);
    expect(c.testResult?.message).not.toContain('SECRET');
    api.listEmbeddingModels.mockReturnValue(throwError(() => new Error('SECRET')));
    c.onReadModels();
    expect(c.listError).not.toContain('SECRET');
    c.form.controls.type.setValue('OPEN_AI');
    api.listEmbeddingModels.mockReturnValue(of(['model']));
    c.onReadModels();
    expect(api.listEmbeddingModels).toHaveBeenLastCalledWith(expect.objectContaining({serverType: 'openai'}));
    const close = vi.spyOn(c.close, 'emit');
    c.onClose();
    expect(close).toHaveBeenCalledOnce();
    fixture.destroy();
  });
  it('busy and test locks guard handlers, destroy cancels pending requests', () => {
    const {fixture, c} = create();
    const pending = new Subject<never>();
    api.testEmbeddingConfig.mockReturnValue(pending);
    c.onTest(); c.onTest(); c.onReadModels(); c.onSave(); c.onClose();
    expect(api.testEmbeddingConfig).toHaveBeenCalledOnce();
    expect(c.form.disabled).toBe(true);
    fixture.destroy();
    expect(pending.observed).toBe(false);
  });
});
