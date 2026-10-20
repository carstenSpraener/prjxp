import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChatModelReference } from '../../models/chat-model-reference.model';

@Component({
  selector: 'chat-model-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './chat-model-card.component.html',
  styleUrl: './chat-model-card.component.css'
})
export class ChatModelCardComponent {
  @Input() model!: ChatModelReference;
  @Output() edit = new EventEmitter<void>();
  @Output() delete = new EventEmitter<void>();

  get serverTypeBadgeClass(): string {
    const type = this.model.serverType?.toLowerCase() ?? 'unknown';
    return `badge-${type.replace(/[^a-z]/g, '')}`;
  }

  get serverTypeLabel(): string {
    const labels: Record<string, string> = {
      'ollama': 'Ollama',
      'openapi': 'OpenAI',
      'lm-studio': 'LM Studio',
      'gemini': 'Gemini',
      'custom': 'Custom'
    };
    return labels[this.model.serverType?.toLowerCase() ?? ''] ?? this.model.serverType ?? 'Unknown';
  }

  get hostFromUrl(): string {
    if (!this.model.providerUrl) return '';
    try {
      const url = new URL(this.model.providerUrl);
      return url.host;
    } catch {
      return this.model.providerUrl;
    }
  }

  onEdit(): void {
    this.edit.emit();
  }

  onDelete(): void {
    console.log('ChatModelCard: onDelete() called, emitting delete event');
    this.delete.emit();
  }
}
