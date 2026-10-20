import { Injectable } from '@angular/core';

const STORAGE_KEY = 'prjxp_chat_settings';

export interface ChatSettingsState {
  model: string;
  projectContext: string | null;
  useTools: boolean;
}

@Injectable({ providedIn: 'root' })
export class ChatSettingsPersistenceService {

  load(): ChatSettingsState | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      return JSON.parse(raw) as ChatSettingsState;
    } catch {
      return null;
    }
  }

  save(state: ChatSettingsState): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch {
      // quota exceeded – silently ignore
    }
  }

  clear(): void {
    localStorage.removeItem(STORAGE_KEY);
  }
}
