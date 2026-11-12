import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ChatModelReference } from '../models/chat-model-reference.model';
import { EmbeddingModelReference } from '../models/embedding-model-reference.model';
import { ModelTestResult } from '../models/model-test-result.model';
import { API_BASE } from '../config/api-config';
import { EmbeddingConfig } from '../models/embedding-config.model';
import { SetupTestResult } from '../models/setup.model';

@Injectable({ providedIn: 'root' })
export class GlobalsService {
  private readonly baseUrl = `${API_BASE}/globals`;
  version: string | null = null;
  chatVersion: string | null = null;
  embeddingVersion: string | null = null;

  constructor(private http: HttpClient) {}

  getChatModels(): Observable<ChatModelReference[]> {
    return this.http.get<ChatModelReference[]>(`${this.baseUrl}/chat-models`, {observe: 'response'})
      .pipe(map(response => {this.chatVersion = this.adopt(response); return response.body ?? [];}));
  }

  createChatModel(model: ChatModelReference, version = this.chatVersion): Observable<ChatModelReference> {
    return this.http.post<ChatModelReference>(`${this.baseUrl}/chat-models`, model,
      {observe: 'response', headers: this.headers(version)}).pipe(map(r => {this.chatVersion = this.adopt(r); return r.body!;}));
  }

  updateChatModel(name: string, model: Partial<ChatModelReference>, version = this.chatVersion): Observable<ChatModelReference> {
    return this.http.put<ChatModelReference>(`${this.baseUrl}/chat-models/${encodeURIComponent(name)}`, model,
      {observe: 'response', headers: this.headers(version)}).pipe(map(r => {this.chatVersion = this.adopt(r); return r.body!;}));
  }

  deleteChatModel(name: string, version = this.chatVersion): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/chat-models/${encodeURIComponent(name)}`,
      {observe: 'response', headers: this.headers(version)}).pipe(map(r => {this.chatVersion = this.adopt(r);}));
  }

  getServerTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/server-types`);
  }

  getEmbeddingModel(): Observable<EmbeddingModelReference> {
    return this.http.get<EmbeddingModelReference>(`${this.baseUrl}/embedding-model`, {observe: 'response'})
      .pipe(map(r => {this.embeddingVersion = this.adopt(r); return r.body!;}));
  }

  updateEmbeddingModel(model: Partial<EmbeddingModelReference>): Observable<EmbeddingModelReference> {
    return this.http.put<EmbeddingModelReference>(`${this.baseUrl}/embedding-model`, model,
      {observe: 'response', headers: this.headers(this.embeddingVersion)})
      .pipe(map(r => {this.embeddingVersion = this.adopt(r); return r.body!;}));
  }

  testChatModel(model: ChatModelReference, version = this.chatVersion): Observable<ModelTestResult> {
    return this.http.post<ModelTestResult>(`${this.baseUrl}/chat-models/test`, model,
      {observe: 'response', headers: this.headers(version)}).pipe(map(r => {this.chatVersion = this.adopt(r); return r.body!;}));
  }

  testEmbeddingModel(model: EmbeddingModelReference): Observable<ModelTestResult> {
    return this.http.post<ModelTestResult>(`${this.baseUrl}/embedding-model/test`, model,
      {observe: 'response', headers: this.headers(this.embeddingVersion)})
      .pipe(map(r => {this.embeddingVersion = this.adopt(r); return r.body!;}));
  }

  listChatModels(params: { serverType: string; providerUrl: string; apiKey: string; stereoType: string }): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/chat-models/models`, { params });
  }

  listEmbeddingModels(params: { serverType: string; providerUrl: string; apiKey: string }): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/embedding-model/models`, { params });
  }
  getEmbeddingConfig(): Observable<EmbeddingConfig> {
    return this.http.get<EmbeddingConfig>(`${this.baseUrl}/embedding-config`, {observe: 'response'})
      .pipe(map(r => {this.embeddingVersion = this.adopt(r); return r.body!;}));
  }
  updateEmbeddingConfig(config: EmbeddingConfig, version: string | null, testId?: string | null): Observable<EmbeddingConfig> {
    return this.http.put<EmbeddingConfig>(`${this.baseUrl}/embedding-config`, config,
      {observe: 'response', headers: this.headers(version, testId)})
      .pipe(map(r => {this.embeddingVersion = this.adopt(r); return r.body!;}));
  }
  testEmbeddingConfig(configuration: EmbeddingConfig, configVersion: string): Observable<SetupTestResult> {
    return this.http.post<SetupTestResult>(`${this.baseUrl}/embedding-config/test`, {configVersion, configuration},
      {observe: 'response', headers: this.headers(configVersion)}).pipe(map(r => {
        this.embeddingVersion = this.adopt(r) ?? r.body!.configVersion;
        this.version = this.embeddingVersion;
        return r.body!;
      }));
  }
  private adopt<T>(response: HttpResponse<T>): string | null {
    const etag = response.headers.get('ETag');
    if (etag) this.version = etag.replace(/^W\//, '').replace(/^"|"$/g, '');
    return etag ? this.version : null;
  }
  private headers(version: string | null, testId?: string | null): HttpHeaders {
    let headers = new HttpHeaders();
    if (version) headers = headers.set('If-Match', `"${version}"`);
    if (testId) headers = headers.set('X-Setup-Test-Id', testId);
    return headers;
  }
}
