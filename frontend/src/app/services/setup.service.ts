import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, finalize, shareReplay, tap } from 'rxjs';
import { API_BASE } from '../config/api-config';
import { ChatModelRequest, EmbeddingConfigRequest, GithubConfigRequest, SetupCompleteRequest,
  SetupConfiguration, SetupSaveRequest, SetupStatus, SetupTestRequest, SetupTestResult } from '../models/setup.model';

@Injectable({ providedIn: 'root' })
export class SetupService {
  private readonly baseUrl = `${API_BASE}/setup`;
  readonly status = signal<SetupStatus | null>(null);
  readonly version = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  private pending?: Observable<SetupStatus>;
  private generation = 0;
  constructor(private http: HttpClient) {}
  publish(status: SetupStatus): void {
    this.generation++;
    this.version.set(status.configVersion);
    this.status.set(status);
    this.error.set(null);
  }
  getStatus(): Observable<SetupStatus> {
    if (this.pending) return this.pending;
    const generation = this.generation;
    this.loading.set(true);
    this.error.set(null);
    this.pending = this.http.get<SetupStatus>(`${this.baseUrl}/status`).pipe(
      tap({ next: status => { if (generation === this.generation) this.publish(status); },
        error: () => this.error.set('Setup-Status nicht erreichbar.') }),
      finalize(() => { this.loading.set(false); this.pending = undefined; }),
      shareReplay({ bufferSize: 1, refCount: true })
    );
    return this.pending;
  }
  getConfiguration(): Observable<SetupConfiguration> {
    return this.http.get<SetupConfiguration>(`${this.baseUrl}/configuration`).pipe(tap(cfg => this.publish(cfg.status)));
  }
  testEmbedding(req: SetupTestRequest<EmbeddingConfigRequest>): Observable<SetupTestResult> {
    return this.test('embedding', req);
  }
  testChat(req: SetupTestRequest<ChatModelRequest>): Observable<SetupTestResult> { return this.test('chat', req); }
  testGithub(req: SetupTestRequest<GithubConfigRequest>): Observable<SetupTestResult> { return this.test('github', req); }
  private test<T>(step: string, req: SetupTestRequest<T>): Observable<SetupTestResult> {
    return this.http.post<SetupTestResult>(`${this.baseUrl}/test-${step}`, req).pipe(
      tap(result => { this.generation++; this.version.set(result.configVersion); })
    );
  }
  save(req: SetupSaveRequest): Observable<SetupStatus> {
    return this.http.post<SetupStatus>(`${this.baseUrl}/save`, req).pipe(tap(status => this.publish(status)));
  }
  complete(req: SetupCompleteRequest): Observable<SetupStatus> {
    return this.http.post<SetupStatus>(`${this.baseUrl}/complete`, req).pipe(tap(status => this.publish(status)));
  }
}
