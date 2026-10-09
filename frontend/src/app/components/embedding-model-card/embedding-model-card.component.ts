import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmbeddingModelReference } from '../../models/embedding-model-reference.model';

@Component({
  selector: 'embedding-model-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './embedding-model-card.component.html',
  styleUrl: './embedding-model-card.component.css'
})
export class EmbeddingModelCardComponent {
  @Input() model: EmbeddingModelReference = {};
  @Output() edit = new EventEmitter<void>();

  get serverTypeBadgeClass(): string {
    const type = this.model.serverType?.toLowerCase() ?? 'unknown';
    return `badge-${type.replace(/[^a-z]/g, '')}`;
  }

  get serverTypeLabel(): string {
    const labels: Record<string, string> = {
      'ollama': 'Ollama',
      'openai': 'OpenAI',
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
}
