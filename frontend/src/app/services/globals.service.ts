import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChatModelReference } from '../models/chat-model-reference.model';
import { EmbeddingModelReference } from '../models/embedding-model-reference.model';
import { ModelTestResult } from '../models/model-test-result.model';
import { API_BASE } from '../config/api-config';

@Injectable({ providedIn: 'root' })
export class GlobalsService {
  private readonly baseUrl = `${API_BASE}/globals`;

  constructor(private http: HttpClient) {}

  getChatModels(): Observable<ChatModelReference[]> {
    return this.http.get<ChatModelReference[]>(`${this.baseUrl}/chat-models`);
  }

  createChatModel(model: ChatModelReference): Observable<ChatModelReference> {
    return this.http.post<ChatModelReference>(`${this.baseUrl}/chat-models`, model);
  }

  updateChatModel(name: string, model: Partial<ChatModelReference>): Observable<ChatModelReference> {
    return this.http.put<ChatModelReference>(`${this.baseUrl}/chat-models/${name}`, model);
  }

  deleteChatModel(name: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/chat-models/${name}`);
  }

  getServerTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/server-types`);
  }

  getEmbeddingModel(): Observable<EmbeddingModelReference> {
    return this.http.get<EmbeddingModelReference>(`${this.baseUrl}/embedding-model`);
  }

  updateEmbeddingModel(model: Partial<EmbeddingModelReference>): Observable<EmbeddingModelReference> {
    return this.http.put<EmbeddingModelReference>(`${this.baseUrl}/embedding-model`, model);
  }

  testChatModel(model: ChatModelReference): Observable<ModelTestResult> {
    return this.http.post<ModelTestResult>(`${this.baseUrl}/chat-models/test`, model);
  }

  testEmbeddingModel(model: EmbeddingModelReference): Observable<ModelTestResult> {
    return this.http.post<ModelTestResult>(`${this.baseUrl}/embedding-model/test`, model);
  }

  listChatModels(params: { serverType: string; providerUrl: string; apiKey: string; stereoType: string }): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/chat-models/models`, { params });
  }

  listEmbeddingModels(params: { serverType: string; providerUrl: string; apiKey: string }): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/embedding-model/models`, { params });
  }
}
