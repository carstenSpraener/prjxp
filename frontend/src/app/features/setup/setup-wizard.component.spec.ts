import { TestBed } from '@angular/core/testing';
import { provideRouter, ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { SetupWizardComponent } from './setup-wizard.component';
import { SetupService } from '../../services/setup.service';
import { GlobalsService } from '../../services/globals.service';
import { SetupTestResult } from '../../models/setup.model';
import { setupConfiguration } from './setup-fixtures';

describe('Setup wizard', () => {
  const result: SetupTestResult = {success: true, message: 'OK', latencyMs: 1, dimensions: 3,
    response: 'hello', testId: 'receipt', configVersion: 'v2'};
  let cfg = setupConfiguration();
  let api: {getConfiguration: ReturnType<typeof vi.fn>; save: ReturnType<typeof vi.fn>;
    complete: ReturnType<typeof vi.fn>; testEmbedding: ReturnType<typeof vi.fn>;
    testChat: ReturnType<typeof vi.fn>; testGithub: ReturnType<typeof vi.fn>; getStatus: ReturnType<typeof vi.fn>};
  beforeEach(() => {
    cfg = setupConfiguration();
    api = {getConfiguration: vi.fn(() => of(cfg)), save: vi.fn(() => of({...cfg.status, configVersion: 'v3'})),
      complete: vi.fn(() => of({...cfg.status, configVersion: 'v4', completed: true, completionMode: 'VERIFIED'})),
      testEmbedding: vi.fn(() => of(result)), testChat: vi.fn(() => of(result)),
      testGithub: vi.fn(() => of({...result, testId: null})), getStatus: vi.fn(() => of(cfg.status))};
    TestBed.configureTestingModule({imports: [SetupWizardComponent],
      providers: [provideRouter([]), {provide: SetupService, useValue: api},
        {provide: GlobalsService, useValue: {getServerTypes: () => of(['ollama', 'openapi', 'gemini'])}}]});
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });
  function create() {
    const fixture = TestBed.createComponent(SetupWizardComponent);
    fixture.detectChanges();
    return {fixture, c: fixture.componentInstance};
  }
  it('prefills and resumes, renders four labels, saves before navigation and preserves drafts on back', () => {
    const {fixture, c} = create();
    expect(fixture.nativeElement.textContent).toContain('Embedding');
    expect(fixture.nativeElement.textContent).toContain('GitHub');
    expect(fixture.nativeElement.textContent).toContain('Chat');
    expect(fixture.nativeElement.textContent).toContain('Optimization');
    c.validity.embedding = true;
    c.next();
    expect(api.save).toHaveBeenCalledWith(expect.objectContaining({step: 'embedding', configVersion: 'v1'}));
    expect(c.currentStep).toBe('github');
    c.back();
    expect(c.drafts.embedding.modelName).toBe('embed');
    fixture.destroy();
  });
  it('invalidates receipts on edits, rejects late responses and guards double clicks', () => {
    const {fixture, c} = create();
    const pending = new Subject<SetupTestResult>();
    api.testEmbedding.mockReturnValue(pending);
    c.test('embedding');
    c.next(); c.back(); c.bypass();
    expect(api.save).not.toHaveBeenCalled();
    c.changeEmbedding({...c.drafts.embedding, modelName: 'new'});
    pending.next(result); pending.complete();
    expect(c.testValid('embedding')).toBe(false);
    c.test('embedding');
    pending.next(result);
    fixture.destroy();
  });
  it('test/save failures retain drafts, conflict reload requires explicit discard confirmation', () => {
    const {fixture, c} = create();
    c.changeEmbedding({...c.drafts.embedding, modelName: 'new'});
    api.testEmbedding.mockReturnValue(throwError(() => ({error: {code: 'CONFIG_CHANGED', message: 'secret'}})));
    c.test('embedding');
    expect(c.error).toContain('CONFIG_CHANGED');
    expect(c.error).not.toContain('secret');
    vi.mocked(window.confirm).mockReturnValue(false);
    c.reload();
    expect(c.drafts.embedding.modelName).toBe('new');
    vi.mocked(window.confirm).mockReturnValue(true);
    c.reload();
    c.validity.embedding = true;
    api.save.mockReturnValue(throwError(() => ({})));
    c.next();
    expect(c.currentStep).toBe('embedding');
    expect(c.error).toBeTruthy();
    fixture.destroy();
  });
  it('optimization retests full candidate then saves and completes using each new version', () => {
    const {fixture, c} = create();
    c.currentStep = 'optimization';
    c.changeOptimization({...c.drafts.optimization, maxSegmentsPerBatch: 8, embeddingTimeout: 30});
    c.validity.optimization = true;
    c.finish();
    expect(api.testEmbedding).toHaveBeenCalledWith({configVersion: 'v1',
      configuration: expect.objectContaining({modelName: 'embed', maxSegmentsPerBatch: 8, timeout: 30})});
    expect(api.save).toHaveBeenCalledWith(expect.objectContaining({configVersion: 'v2', step: 'optimization',
      testId: 'receipt', optimization: expect.objectContaining({vectorDimension: 3})}));
    expect(api.complete).toHaveBeenCalledWith({configVersion: 'v3', defer: false});
    expect(c.finished).toBe(true);
    fixture.destroy();
  });
  it('complete failure retains persisted partial progress and retry does not save again', () => {
    const {fixture, c} = create();
    c.currentStep = 'optimization'; c.validity.optimization = true;
    api.complete.mockReturnValueOnce(throwError(() => ({error: {code: 'SETUP_NOT_VERIFIED'}})));
    c.finish();
    expect(c.optimizationSaved).toBe(true);
    expect(c.finished).toBe(false);
    c.finish();
    expect(api.save).toHaveBeenCalledOnce();
    expect(api.complete).toHaveBeenCalledTimes(2);
    fixture.destroy();
  });
  it('bypass preserves drafts on failure, confirms discard and never saves', () => {
    const {fixture, c} = create();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    c.changeEmbedding({...c.drafts.embedding, apiKey: 'private'});
    expect(c.canDeactivate()).toBe(true);
    const event = new Event('beforeunload', {cancelable: true});
    c.beforeUnload(event);
    expect(event.defaultPrevented).toBe(true);
    api.complete.mockReturnValueOnce(throwError(() => ({})));
    api.complete.mockReturnValueOnce(of({...cfg.status, completed: true, completionMode: 'DEFERRED'}));
    c.bypass();
    expect(c.dirty).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    c.bypass();
    expect(api.save).not.toHaveBeenCalled();
    expect(api.complete).toHaveBeenLastCalledWith({configVersion: 'v1', defer: true});
    expect(navigate).toHaveBeenCalledWith(['/dashboard']);
    expect(c.dirty).toBe(false);
    fixture.destroy();
  });
  it('loading/error retry and rerun start at embedding; restart check remains explicit', () => {
    api.getConfiguration.mockReturnValueOnce(throwError(() => ({})));
    const {fixture, c} = create();
    expect(c.error).toBeTruthy();
    c.next(); c.finish();
    expect(api.save).not.toHaveBeenCalled();
    cfg.status.resumeStep = 'chat';
    c.reload();
    expect(c.currentStep).toBe('chat');
    api.getStatus.mockReturnValueOnce(throwError(() => ({})));
    c.checkStatus();
    expect(c.error).toBeTruthy();
    c.checkStatus();
    expect(c.status?.restartRequired).toBe(false);
    fixture.destroy();
  });
  it('rerun prefills without changing completion marker; page loading guards all requests', () => {
    const pending = new Subject<typeof cfg>();
    cfg.status.completed = true; cfg.status.resumeStep = 'optimization';
    api.getConfiguration.mockReturnValueOnce(pending);
    TestBed.overrideProvider(ActivatedRoute, {useValue: {snapshot: {queryParamMap: convertToParamMap({rerun: '1'})},
      queryParamMap: of(convertToParamMap({rerun: '1'}))}});
    const {fixture, c} = create();
    c.next(); c.test('embedding'); c.finish(); c.bypass();
    expect(api.save).not.toHaveBeenCalled();
    expect(c.canDeactivate()).toBe(false);
    pending.next(cfg); pending.complete();
    fixture.detectChanges();
    expect(c.currentStep).toBe('embedding');
    expect(c.status?.completed).toBe(true);
    expect(document.activeElement?.id).toBe('setup-step-title');
    fixture.destroy();
  });
  it('github save/skip are explicit, confirmed, versioned and keep failed drafts', () => {
    const {fixture, c} = create();
    c.currentStep = 'github';
    fixture.detectChanges();
    c.changeGithub({...c.drafts.github, defaultPat: 'private'});
    c.test('github');
    expect(api.testGithub).toHaveBeenCalledWith(expect.objectContaining({configVersion: 'v1'}));
    vi.mocked(window.confirm).mockReturnValue(false);
    c.skip();
    expect(api.save).not.toHaveBeenCalled();
    vi.mocked(window.confirm).mockReturnValue(true);
    api.save.mockReturnValueOnce(throwError(() => ({})));
    c.skip();
    expect(c.drafts.github.defaultPat).toBe('private');
    c.skip();
    expect(c.currentStep).toBe('chat');
    expect(c.drafts.github.defaultPat).toBeNull();
    fixture.detectChanges();
    c.changeChat({...c.drafts.chat, modelName: 'new-chat'});
    c.test('chat');
    c.next();
    expect(api.save).toHaveBeenLastCalledWith(expect.objectContaining({step: 'chat', configVersion: 'v2', testId: 'receipt'}));
    fixture.detectChanges();
    expect(c.currentStep).toBe('optimization');
    expect(fixture.nativeElement.textContent).toContain('Fertig');
    fixture.destroy();
  });
  it('failed same-snapshot test blocks persisted proof; invalid/late results never expose dimensions', () => {
    const {fixture, c} = create();
    api.testEmbedding.mockReturnValueOnce(throwError(() => ({})));
    c.test('embedding');
    expect(c.testValid('embedding')).toBe(false);
    expect(c.measuredDimension).toBeNull();
    c.next();
    expect(api.save).not.toHaveBeenCalled();
    api.testEmbedding.mockReturnValueOnce(of({...result, success: false, dimensions: 300}));
    c.test('embedding');
    fixture.detectChanges();
    expect(c.measuredDimension).toBeNull();
    c.currentStep = 'chat'; fixture.detectChanges();
    api.testChat.mockReturnValueOnce(of({...result, response: '  '}));
    c.test('chat');
    expect(c.testValid('chat')).toBe(false);
    fixture.destroy();
  });
  it('optimization failures stop sequence, skip resets overrides only after confirmed discard', () => {
    const {fixture, c} = create();
    c.currentStep = 'optimization'; fixture.detectChanges();
    c.changeOptimization({...c.drafts.optimization, embeddingTimeout: 40});
    c.validity.optimization = true;
    api.testEmbedding.mockReturnValueOnce(of({...result, success: false}));
    c.finish();
    expect(api.save).not.toHaveBeenCalled();
    expect(api.complete).not.toHaveBeenCalled();
    vi.mocked(window.confirm).mockReturnValue(false);
    c.finish(true);
    expect(c.drafts.embedding.timeout).toBe(40);
    vi.mocked(window.confirm).mockReturnValue(true);
    c.finish(true);
    expect(api.save).toHaveBeenCalledWith({configVersion: 'v2', step: 'optimization', skip: true});
    expect(c.drafts.embedding.timeout).toBe(20);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Setup abgeschlossen');
    expect(fixture.nativeElement.textContent).toContain('kein Neustart');
    fixture.destroy();
  });
  it('restart screen does not claim activation until a fresh reachable status confirms it', () => {
    const {fixture, c} = create();
    c.currentStep = 'optimization'; fixture.detectChanges();
    api.complete.mockReturnValueOnce(of({...cfg.status, completed: true, completionMode: 'VERIFIED', restartRequired: true}));
    c.finish();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Backend neu starten');
    api.getStatus.mockReturnValueOnce(throwError(() => ({})));
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    expect(c.error).toBeTruthy();
    c.checkStatus();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('kein Neustart');
    fixture.destroy();
  });
  it('save response cannot mark a later programmatic edit clean or advance it', () => {
    const {fixture, c} = create();
    const pending = new Subject<typeof cfg.status>();
    api.save.mockReturnValueOnce(pending);
    c.next();
    c.changeEmbedding({...c.drafts.embedding, modelName: 'edited-during-save'});
    pending.next({...cfg.status, configVersion: 'new'}); pending.complete();
    expect(c.currentStep).toBe('embedding');
    expect(c.dirty).toBe(true);
    expect(c.drafts.embedding.modelName).toBe('edited-during-save');
    expect(c.testValid('embedding')).toBe(false);
    expect(c.configVersion).toBe('new');
    fixture.destroy();
  });
  it('finish saves its captured snapshot but refuses Complete after a later revision', () => {
    const {fixture, c} = create();
    c.currentStep = 'optimization'; fixture.detectChanges();
    const pending = new Subject<typeof cfg.status>();
    api.save.mockReturnValueOnce(pending);
    c.finish();
    c.changeOptimization({...c.drafts.optimization, docpipeMaxThreads: 7});
    pending.next({...cfg.status, configVersion: 'new'}); pending.complete();
    expect(api.complete).not.toHaveBeenCalled();
    expect(c.dirty).toBe(true);
    expect(c.drafts.optimization.docpipeMaxThreads).toBe(7);
    expect(c.configVersion).toBe('new');
    fixture.destroy();
  });
  it('a failed stored embedding observation blocks even optimization skip and Complete', () => {
    const {fixture, c} = create();
    api.testEmbedding.mockReturnValueOnce(of({...result, success: false}));
    c.test('embedding');
    c.currentStep = 'optimization'; fixture.detectChanges();
    c.finish(true);
    expect(api.save).not.toHaveBeenCalled();
    expect(api.complete).not.toHaveBeenCalled();
    fixture.destroy();
  });
  it('property order is not a candidate edit; unchanged form emissions retain saved proof and dimension', () => {
    const {fixture, c} = create();
    c.changeEmbedding({resetDimensions: false, maxSegmentsPerBatch: 32, timeout: 20, dimensions: 3,
      modelName: 'embed', apiKey: null, apiBaseURL: 'http://localhost:11434', type: 'OLLAMA'});
    expect(c.dirty).toBe(false);
    expect(c.testValid('embedding')).toBe(true);
    expect(c.drafts.optimization.vectorDimension).toBe(3);
    fixture.destroy();
  });
});
