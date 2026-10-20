import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarkdownComponent } from 'ngx-markdown';
import { ChatMessage } from '../../models/chat-message.model';

@Component({
  selector: 'chat-message',
  standalone: true,
  imports: [CommonModule, MarkdownComponent],
  templateUrl: './chat-message.component.html',
  styleUrl: './chat-message.component.css'
})
export class ChatMessageComponent {
  @Input() message!: ChatMessage;

  get isUser(): boolean {
    return this.message?.role === 'user';
  }

  get isThinking(): boolean {
    return this.message?.role === 'thinking';
  }

  get isAssistant(): boolean {
    return this.message?.role === 'assistant';
  }

  get hasRagContext(): boolean {
    return (this.message?.ragContext?.length ?? 0) > 0;
  }

  get hasToolCalls(): boolean {
    return (this.message?.toolCalls?.length ?? 0) > 0;
  }

  showRagContext = false;
  showToolCalls = false;

  toggleRagContext(): void {
    this.showRagContext = !this.showRagContext;
  }

  toggleToolCalls(): void {
    this.showToolCalls = !this.showToolCalls;
  }
}
