import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SetupService } from './setup.service';
import { API_BASE } from '../config/api-config';
import { setupConfiguration } from '../features/setup/setup-fixtures';

describe('SetupService', () => {
  let service: SetupService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(SetupService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('deduplicates status requests and publishes configuration and mutations', () => {
    const cfg = setupConfiguration();
    service.getStatus().subscribe();
    service.getStatus().subscribe();
    http.expectOne(`${API_BASE}/setup/status`).flush(cfg.status);
    expect(service.status()).toEqual(cfg.status);
    service.getConfiguration().subscribe();
    http.expectOne(`${API_BASE}/setup/configuration`).flush(cfg);
    service.save({configVersion: 'v1', step: 'github', skip: true}).subscribe();
    http.expectOne(`${API_BASE}/setup/save`).flush({...cfg.status, configVersion: 'v2'});
    expect(service.status()?.configVersion).toBe('v2');
    service.complete({configVersion: 'v2', defer: true}).subscribe();
    const complete = http.expectOne(`${API_BASE}/setup/complete`);
    expect(complete.request.body).toEqual({configVersion: 'v2', defer: true});
    complete.flush({...cfg.status, completed: true});
  });
  it('uses the three exact test envelopes and adopts response versions', () => {
    const cfg = setupConfiguration();
    for (const [path, request] of [
      ['test-embedding', service.testEmbedding({configVersion: 'v1', configuration: cfg.embedding})],
      ['test-chat', service.testChat({configVersion: 'v1', configuration: cfg.chat})],
      ['test-github', service.testGithub({configVersion: 'v1', configuration: cfg.github})]
    ] as const) {
      request.subscribe();
      const req = http.expectOne(`${API_BASE}/setup/${path}`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.configVersion).toBe('v1');
      req.flush({success: true, configVersion: 'v3', testId: 'receipt', dimensions: 3});
    }
    expect(service.version()).toBe('v3');
  });
  it('keeps errors explicit without inventing status', () => {
    service.getStatus().subscribe({error: () => {}});
    http.expectOne(`${API_BASE}/setup/status`).flush({}, {status: 503, statusText: 'Offline'});
    expect(service.status()).toBeNull();
    expect(service.error()).toBeTruthy();
    expect(service.loading()).toBe(false);
  });
});
