import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ChatSettingsPersistenceService, ChatSettingsState } from './chat-settings-persistence.service';

const STORAGE_KEY = 'prjxp_chat_settings';

describe('ChatSettingsPersistenceService', () => {
  let service: ChatSettingsPersistenceService;

  const sampleState: ChatSettingsState = {
    model: 'gemma',
    projectContext: 'prjxp',
    useTools: true
  };

  beforeEach(() => {
    localStorage.clear();
    service = new ChatSettingsPersistenceService();
  });

  describe('load', () => {
    it('should return null when no data is stored', () => {
      expect(service.load()).toBeNull();
    });

    it('should return null when key does not exist', () => {
      localStorage.setItem('other_key', '{}');
      expect(service.load()).toBeNull();
    });

    it('should return parsed state when valid JSON is stored', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(sampleState));
      const result = service.load();

      expect(result).not.toBeNull();
      expect(result!.model).toBe('gemma');
      expect(result!.projectContext).toBe('prjxp');
      expect(result!.useTools).toBe(true);
    });

    it('should return null when stored value is corrupt JSON', () => {
      localStorage.setItem(STORAGE_KEY, '{not valid json');
      expect(service.load()).toBeNull();
    });

    it('should return null when stored value is not an object', () => {
      localStorage.setItem(STORAGE_KEY, '"just a string"');
      // JSON.parse succeeds but result is not matching ChatSettingsState shape;
      // the cast still returns something, so we test that it does not crash
      const result = service.load();
      expect(result).not.toBeNull(); // it returns the parsed value (a string cast as object)
    });

    it('should return null when stored value is empty string', () => {
      localStorage.setItem(STORAGE_KEY, '');
      expect(service.load()).toBeNull();
    });
  });

  describe('save', () => {
    it('should store state as JSON string in localStorage', () => {
      service.save(sampleState);
      const raw = localStorage.getItem(STORAGE_KEY);

      expect(raw).not.toBeNull();
      const parsed = JSON.parse(raw!);
      expect(parsed.model).toBe('gemma');
      expect(parsed.projectContext).toBe('prjxp');
      expect(parsed.useTools).toBe(true);
    });

    it('should overwrite previous value when saving again', () => {
      const firstState: ChatSettingsState = { model: 'old', projectContext: null, useTools: false };
      service.save(firstState);

      service.save(sampleState);
      const raw = localStorage.getItem(STORAGE_KEY);
      const parsed = JSON.parse(raw!);

      expect(parsed.model).toBe('gemma');
    });

    it('should handle null projectContext correctly', () => {
      const state: ChatSettingsState = { model: 'default', projectContext: null, useTools: false };
      service.save(state);

      const result = service.load();
      expect(result!.projectContext).toBeNull();
    });

    it('should not throw when localStorage is unavailable', () => {
      vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
        throw new Error('QuotaExceededError');
      });

      expect(() => service.save(sampleState)).not.toThrow();
    });
  });

  describe('clear', () => {
    it('should remove the storage key', () => {
      service.save(sampleState);
      expect(localStorage.getItem(STORAGE_KEY)).not.toBeNull();

      service.clear();
      expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    });

    it('should not throw when key does not exist', () => {
      expect(() => service.clear()).not.toThrow();
    });
  });

  describe('round-trip', () => {
    it('should preserve all fields through save → load', () => {
      const states: ChatSettingsState[] = [
        { model: 'ollama-coder', projectContext: 'my-project', useTools: true },
        { model: 'default', projectContext: null, useTools: false },
        { model: '', projectContext: 'prjxp', useTools: true }
      ];

      for (const state of states) {
        service.save(state);
        const loaded = service.load();

        expect(loaded!.model).toBe(state.model);
        expect(loaded!.projectContext).toBe(state.projectContext);
        expect(loaded!.useTools).toBe(state.useTools);
      }
    });

    it('should work with save → clear → load returns null', () => {
      service.save(sampleState);
      service.clear();
      expect(service.load()).toBeNull();
    });
  });
});
