import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { ChatService, ChatSseEvent } from './chat.service';
import { vi } from 'vitest';

// Mock fetch globally for tests
const mockFetch = vi.fn();
globalThis.fetch = mockFetch;

describe('ChatService', () => {
  let service: ChatService;

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [ChatService, HttpClient]
    });
    service = TestBed.inject(ChatService);
  });

  describe('sendStreaming', () => {
    it('should send phase events and complete event from SSE stream', async () => {
      const sseResponse = `event: phase\ndata: {"phase":"llm_generating","message":"LLM generiert Antwort..."}

event: complete\ndata: {"id":"resp-1","role":"assistant","content":"Hello there"}

`;

      let readCallCount = 0;
      // The mock reader delivers chunks[0..len-2] and then signals done,
      // so the final '' entry acts as the done marker. All data chunks must
      // come before it to cover the entire response.
      const chunks = [
        sseResponse.slice(0, 60),
        sseResponse.slice(60, 120),
        sseResponse.slice(120),
        ''
      ];
      const mockReader = {
        read: vi.fn().mockImplementation(() => {
          const idx = readCallCount++;
          if (idx < chunks.length - 1) {
            return Promise.resolve({ done: false, value: new TextEncoder().encode(chunks[idx]) });
          }
          return Promise.resolve({ done: true, value: '' });
        })
      };

      mockFetch.mockReturnValue(Promise.resolve({
        body: { getReader: () => mockReader }
      }));

      const phaseEvents: any[] = [];
      let completeEvent: any = null;

      await new Promise<void>((resolve) => {
        service.sendStreaming('test', 'model').subscribe({
          next: (event: ChatSseEvent) => {
            if (event.event === 'phase') phaseEvents.push(event);
            if (event.event === 'complete') completeEvent = event;
          },
          error: () => { /* should not error */ },
          complete: () => {
            expect(phaseEvents.length).toBeGreaterThanOrEqual(1);
            expect(completeEvent).not.toBeNull();
            if (completeEvent) {
              expect(completeEvent.data.content).toBe('Hello there');
            }
            resolve();
          }
        });
      });
    });

    it('should emit error when fetch fails', async () => {
      mockFetch.mockReturnValue(Promise.reject(new Error('Network error')));

      await new Promise<void>((resolve) => {
        service.sendStreaming('test', 'model').subscribe({
          next: () => { /* should not emit */ },
          error: (err) => {
            expect(err.message).toBe('Network error');
            resolve();
          },
          complete: () => { /* should not complete */ }
        });
      });
    });

    it('should emit error when no reader is available', async () => {
      mockFetch.mockReturnValue(Promise.resolve({
        body: null
      }));

      await new Promise<void>((resolve) => {
        service.sendStreaming('test', 'model').subscribe({
          next: () => { /* should not emit */ },
          error: (err) => {
            expect(err.message).toBe('No stream available');
            resolve();
          },
          complete: () => { /* should not complete */ }
        });
      });
    });

    it('should send correct request body with all parameters', async () => {
      const mockReader = {
        read: vi.fn().mockReturnValue(Promise.resolve({ done: true, value: '' }))
      };

      mockFetch.mockReturnValue(Promise.resolve({
        body: { getReader: () => mockReader }
      }));

      await new Promise<void>((resolve) => {
        service.sendStreaming(
          'my message',
          'gemma',
          'prjxp-project',
          true,
          [{ role: 'user', content: 'history msg' }]
        ).subscribe({
          complete: () => {
            expect(mockFetch).toHaveBeenCalled();
            const callArgs = mockFetch.mock.calls[0];
            const options = callArgs[1] as RequestInit;
            const body = JSON.parse(options.body as string);

            expect(body.message).toBe('my message');
            expect(body.chatModelName).toBe('gemma');
            expect(body.projectContext).toBe('prjxp-project');
            expect(body.useTools).toBe(true);
            expect(body.messages).toHaveLength(1);
            resolve();
          }
        });
      });
    });

    it('should include Accept header for SSE', async () => {
      const mockReader = {
        read: vi.fn().mockReturnValue(Promise.resolve({ done: true, value: '' }))
      };

      mockFetch.mockReturnValue(Promise.resolve({
        body: { getReader: () => mockReader }
      }));

      await new Promise<void>((resolve) => {
        service.sendStreaming('test', 'model').subscribe({
          complete: () => {
            const callArgs = mockFetch.mock.calls[0];
            const options = callArgs[1] as RequestInit;
            expect(options.headers).toHaveProperty('Accept', 'text/event-stream');
            resolve();
          }
        });
      });
    });

    it('should handle error event from server', async () => {
      const sseResponse = `event: error\ndata: {"message":"Something went wrong"}

`;

      let readCallCount = 0;
      const mockReader = {
        read: vi.fn().mockImplementation(() => {
          readCallCount++;
          if (readCallCount === 1) {
            return Promise.resolve({ done: false, value: new TextEncoder().encode(sseResponse) });
          }
          return Promise.resolve({ done: true, value: '' });
        })
      };

      mockFetch.mockReturnValue(Promise.resolve({
        body: { getReader: () => mockReader }
      }));

      let errorEvent: any = null;

      await new Promise<void>((resolve) => {
        service.sendStreaming('test', 'model').subscribe({
          next: (event: ChatSseEvent) => {
            if (event.event === 'error') errorEvent = event;
          },
          error: () => { /* expected to complete, not error */ },
          complete: () => {
            expect(errorEvent).not.toBeNull();
            if (errorEvent) {
              expect(errorEvent.data.message).toBe('Something went wrong');
            }
            resolve();
          }
        });
      });
    });
  });

  describe('send (non-streaming)', () => {
    // The send() method uses HttpClient which is harder to mock in unit tests.
    // Integration tests would cover this more thoroughly.
    it('should exist', () => {
      expect(service.send).toBeDefined();
    });
  });

  describe('getAvailableProjects', () => {
    it('should exist', () => {
      expect(service.getAvailableProjects).toBeDefined();
    });
  });

  describe('getAvailableTools', () => {
    it('should exist', () => {
      expect(service.getAvailableTools).toBeDefined();
    });
  });
});
