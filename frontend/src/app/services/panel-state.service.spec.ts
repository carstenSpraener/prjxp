import { TestBed } from '@angular/core/testing';
import { PanelStateService } from './panel-state.service';
import { ChatMessage } from '../models/chat-message.model';

describe('PanelStateService', () => {
  let service: PanelStateService;

  beforeEach(() => {
    // Clear localStorage before each test
    localStorage.clear();
    TestBed.configureTestingModule({});
    service = TestBed.inject(PanelStateService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('getChatMessages', () => {
    it('should return empty array when no messages are stored', () => {
      expect(service.getChatMessages()).toEqual([]);
    });

    it('should return stored messages', () => {
      const msgs: ChatMessage[] = [
        { id: '1', role: 'user', content: 'Hello' },
        { id: '2', role: 'assistant', content: 'Hi there!' }
      ];
      service.setChatMessages(msgs);

      expect(service.getChatMessages()).toEqual(msgs);
    });
  });

  describe('setChatMessages', () => {
    it('should persist messages to localStorage', () => {
      const msgs: ChatMessage[] = [
        { id: '1', role: 'user', content: 'Test message' }
      ];
      service.setChatMessages(msgs);

      const stored = localStorage.getItem('prjxp_panel_state');
      expect(stored).toBeTruthy();
      expect(JSON.parse(stored!)).toHaveProperty('chatMessages');
    });

    it('should overwrite previous messages', () => {
      const first: ChatMessage[] = [{ id: '1', role: 'user', content: 'First' }];
      const second: ChatMessage[] = [{ id: '2', role: 'user', content: 'Second' }];

      service.setChatMessages(first);
      service.setChatMessages(second);

      expect(service.getChatMessages()).toEqual(second);
    });
  });

  describe('clearChatMessages', () => {
    it('should remove messages from state and storage', () => {
      const msgs: ChatMessage[] = [{ id: '1', role: 'user', content: 'To be cleared' }];
      service.setChatMessages(msgs);
      expect(service.getChatMessages()).toHaveLength(1);

      service.clearChatMessages();
      expect(service.getChatMessages()).toEqual([]);

      const stored = localStorage.getItem('prjxp_panel_state');
      // After clear, the chatMessages key should be removed
      if (stored) {
        const parsed = JSON.parse(stored);
        expect(parsed.chatMessages).toBeUndefined();
      }
    });

    it('should handle clearing when no messages exist', () => {
      // Should not throw
      service.clearChatMessages();
      expect(service.getChatMessages()).toEqual([]);
    });
  });

  describe('persistence across instances', () => {
    it('should survive service reinjection (simulating navigation)', () => {
      const msgs: ChatMessage[] = [{ id: '1', role: 'user', content: 'Persistent' }];
      service.setChatMessages(msgs);

      // Simulate getting a fresh instance (as Angular would after navigation)
      const newService = TestBed.inject(PanelStateService);
      // Since it's singleton (providedIn: 'root'), it should be the same instance
      expect(newService).toBe(service);
      expect(newService.getChatMessages()).toEqual(msgs);
    });
  });

  describe('error handling', () => {
    it('should handle localStorage quota exceeded gracefully on setChatMessages', () => {
      // Override localStorage.setItem to throw
      const originalSetItem = localStorage.setItem;
      localStorage.setItem = () => { throw new Error('QuotaExceededError'); };

      // Should not throw
      service.setChatMessages([{ id: '1', role: 'user', content: 'test' }]);

      // Restore
      localStorage.setItem = originalSetItem;
    });

    it('should handle invalid JSON in localStorage on load', () => {
      // Write invalid JSON to storage
      localStorage.setItem('prjxp_panel_state', '{invalid json}');

      // Create a new instance to trigger the load
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({});
      const freshService = TestBed.inject(PanelStateService);

      // Should return empty array on parse error
      expect(freshService.getChatMessages()).toEqual([]);
    });
  });
});
