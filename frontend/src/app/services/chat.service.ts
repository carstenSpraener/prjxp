import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChatMessage } from '../models/chat-message.model';
import { API_BASE } from '../config/api-config';

export interface ChatRequestDto {
  message: string;
  chatModelName: string;
  projectContext?: string | null;
  useTools?: boolean;
  messages?: { role: 'user' | 'assistant'; content: string }[];
}

export interface ChatResponseDto {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  ragContext?: Array<{
    projectName: string;
    chunkId: string;
    snippet: string;
    score: number;
  }>;
  toolCalls?: Array<{
    toolName: string;
    arguments: Record<string, unknown>;
    result: string;
  }>;
}

export interface ChatSseEvent {
  event: 'phase' | 'complete' | 'error';
  data: any;
}

export interface ChatPhaseData {
  phase: 'rag_search' | 'llm_generating' | 'tools' | 'complete';
  message?: string;
  toolCalls?: Array<{
    toolName: string;
    arguments: Record<string, unknown>;
    result: string;
  }>;
  content?: string;
  ragContext?: Array<{
    projectName: string;
    chunkId: string;
    snippet: string;
    score: number;
  }>;
  id?: string;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly baseUrl = `${API_BASE}/chat`;

  constructor(private http: HttpClient) {}

  send(message: string, modelName: string, projectContext?: string | null, useTools = false, history?: { role: 'user' | 'assistant'; content: string }[]): Observable<ChatResponseDto> {
    const body: ChatRequestDto = {
      message,
      chatModelName: modelName,
      projectContext,
      useTools,
      messages: history
    };
    console.log('ChatService: send() request body', JSON.stringify(body));
    return this.http.post<ChatResponseDto>(`${this.baseUrl}/send`, body);
  }

  sendStreaming(message: string, modelName: string, projectContext?: string | null, useTools = false, history?: { role: 'user' | 'assistant'; content: string }[]): Observable<ChatSseEvent> {
    const body: ChatRequestDto = {
      message,
      chatModelName: modelName,
      projectContext,
      useTools,
      messages: history
    };

    return new Observable<ChatSseEvent>(observer => {
      fetch(`${this.baseUrl}/send`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'text/event-stream'
        },
        body: JSON.stringify(body)
      }).then(async response => {
        const reader = response.body?.getReader();
        if (!reader) {
          observer.error(new Error('No stream available'));
          return;
        }

        const decoder = new TextDecoder();
        let buffer = '';

        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });

            // SSE messages are separated by double newlines
            const parts = buffer.split('\n\n');
            // Keep the last (potentially incomplete) part in the buffer
            buffer = parts.pop() || '';

            for (const part of parts) {
              const lines = part.trim().split('\n');
              let eventName = 'message';
              let dataStr = '';

              for (const line of lines) {
                if (line.startsWith('event:')) {
                  eventName = line.slice(6).trim();
                } else if (line.startsWith('data:')) {
                  dataStr = line.slice(5).trim();
                }
              }

              if (dataStr) {
                try {
                  const data = JSON.parse(dataStr);
                  observer.next({ event: eventName as any, data });
                } catch {
                  // Skip non-JSON data lines
                }
              }
            }
          }
          observer.complete();
        } catch (err) {
          observer.error(err);
        }
      }).catch(err => {
        observer.error(err);
      });
    });
  }

  getAvailableProjects(): Observable<string[]> {
    return this.http.post<string[]>(`${this.baseUrl}/projects`, {});
  }

  getAvailableTools(): Observable<string[]> {
    return this.http.post<string[]>(`${this.baseUrl}/tools`, {});
  }
}
