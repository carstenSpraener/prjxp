import { Injectable } from '@angular/core';
import { ChatMessage } from '../models/chat-message.model';

const STORAGE_KEY = 'prjxp_panel_state';

export interface PanelState {
  chatMessages?: ChatMessage[];
}

@Injectable({ providedIn: 'root' })
export class PanelStateService {
  private state: PanelState = this.load();

  private load(): PanelState {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : {};
    } catch {
      return {};
    }
  }

  private save(): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.state));
    } catch {
      // quota exceeded – silently ignore
    }
  }

  getChatMessages(): ChatMessage[] {
    return this.state.chatMessages || [];
  }

  setChatMessages(messages: ChatMessage[]): void {
    this.state.chatMessages = messages;
    this.save();
  }

  clearChatMessages(): void {
    delete this.state.chatMessages;
    this.save();
  }
}
