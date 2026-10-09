import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChatModelReference } from '../models/chat-model-reference.model';
import { EmbeddingModelReference } from '../models/embedding-model-reference.model';
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
}
