import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, Subject, Observable, throwError } from 'rxjs';
import { AppComponent } from './app';
import { SetupService } from './services/setup.service';
import { PrjxpApiService } from './services/prjxp-api.service';
import { setupConfiguration } from './features/setup/setup-fixtures';
import { API_BASE } from './config/api-config';
import { GlobalsService } from './services/globals.service';
import { routes } from './app.routes';
import { RouterTestingHarness } from '@angular/router/testing';
import { SetupWizardComponent } from './features/setup/setup-wizard.component';

describe('Setup integration contracts', () => {
  beforeEach(() => TestBed.configureTestingModule({imports: [AppComponent], providers: [
    provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
    {provide: PrjxpApiService, useValue: {ping: () => of('pong')}}
  ]}));
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('setup route precedes catch-all and protects unsaved navigation', () => {
    const setup = routes.find(route => route.path === 'setup');
    expect(setup?.canDeactivate).toHaveLength(1);
    expect(routes.indexOf(setup!)).toBeLessThan(routes.findIndex(route => route.path === '**'));
  });
  it('redirects only successful incomplete status and checks again on reconnect', () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(AppComponent);
    const http = TestBed.inject(HttpTestingController);
    expect(navigate).not.toHaveBeenCalled();
    http.expectOne(`${API_BASE}/setup/status`).flush(setupConfiguration().status);
    expect(navigate).toHaveBeenCalledWith(['/setup'], {replaceUrl: true});
    fixture.destroy();
  });
  it('does not redirect for completed, unknown, failure or stale status responses', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(AppComponent);
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne(`${API_BASE}/setup/status`);
    TestBed.inject(SetupService).publish({...setupConfiguration().status, completed: true, configVersion: 'new'});
    request.flush(setupConfiguration().status);
    expect(navigate).not.toHaveBeenCalled();
    fixture.destroy();
  });
  it('heartbeat reconnect retries errors, deduplicates online checks and respects completed/setup routes', () => {
    vi.useFakeTimers();
    const ping = vi.fn().mockReturnValueOnce(throwError(() => ({}))).mockReturnValue(of('pong'));
    TestBed.overrideProvider(PrjxpApiService, {useValue: {ping}});
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(`${API_BASE}/setup/status`).flush({}, {status: 503, statusText: 'Offline'});
    expect(navigate).not.toHaveBeenCalled();
    vi.advanceTimersByTime(5000);
    http.expectOne(`${API_BASE}/setup/status`).flush({...setupConfiguration().status, completed: true});
    expect(navigate).not.toHaveBeenCalled();
    vi.advanceTimersByTime(5000);
    http.expectNone(`${API_BASE}/setup/status`);
    ping.mockReturnValueOnce(throwError(() => ({})));
    vi.advanceTimersByTime(5000);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/setup?rerun=1');
    vi.advanceTimersByTime(5000);
    http.expectOne(`${API_BASE}/setup/status`).flush(setupConfiguration().status);
    expect(navigate).not.toHaveBeenCalled();
    fixture.destroy();
    vi.useRealTimers();
  });
  it('direct setup route restores configuration, rerun and required form state', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()]});
    const harness = await RouterTestingHarness.create('/setup?rerun=1');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(`${API_BASE}/setup/configuration`).flush(setupConfiguration());
    http.expectOne(`${API_BASE}/globals/server-types`).flush(['ollama', 'openapi']);
    harness.detectChanges();
    const wizard = harness.routeDebugElement!.componentInstance as SetupWizardComponent;
    expect(wizard.currentStep).toBe('embedding');
    expect(wizard.drafts.embedding.modelName).toBe('embed');
    expect(wizard.canProceed()).toBe(true);
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    wizard.changeEmbedding({...wizard.drafts.embedding, apiKey: 'test-only-secret'});
    expect(storage).not.toHaveBeenCalled();
  });
  it('globals use quoted ETags and explicit If-Match for all CRUD and setup test envelopes', () => {
    const globals = TestBed.inject(GlobalsService);
    const http = TestBed.inject(HttpTestingController);
    globals.getEmbeddingConfig().subscribe();
    http.expectOne(`${API_BASE}/globals/embedding-config`).flush(setupConfiguration().embedding,
      {headers: {ETag: '"version 1"'}});
    expect(globals.version).toBe('version 1');
    globals.updateEmbeddingConfig({dimensions: null, resetDimensions: true}, globals.version!, 'receipt').subscribe();
    const save = http.expectOne(`${API_BASE}/globals/embedding-config`);
    expect(save.request.headers.get('If-Match')).toBe('"version 1"');
    expect(save.request.headers.get('X-Setup-Test-Id')).toBe('receipt');
    save.flush({}, {headers: {ETag: '"v2"'}});
    globals.testEmbeddingConfig({type: 'OLLAMA'}, 'v2').subscribe();
    const test = http.expectOne(`${API_BASE}/globals/embedding-config/test`);
    expect(test.request.body).toEqual({configVersion: 'v2', configuration: {type: 'OLLAMA'}});
    expect(test.request.headers.get('If-Match')).toBe('"v2"');
    test.flush({configVersion: 'v3'});
    for (const action of [
      () => globals.createChatModel({stereoType: 'default'}, 'v3'),
      () => globals.updateChatModel('default', {modelName: 'new'}, 'v3'),
      () => globals.deleteChatModel('default', 'v3')
    ]) {
      (action() as Observable<unknown>).subscribe();
      const request = http.expectOne(r => r.url.includes('/chat-models'));
      expect(request.request.headers.get('If-Match')).toBe('"v3"');
      request.flush({}, {headers: {ETag: '"v4"'}});
    }
  });
});
