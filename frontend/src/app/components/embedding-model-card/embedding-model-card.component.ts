import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EmbeddingConfig } from '../../models/embedding-config.model';

@Component({
  selector: 'embedding-model-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './embedding-model-card.component.html',
  styleUrl: './embedding-model-card.component.css'
})
export class EmbeddingModelCardComponent {
  @Input() model: EmbeddingConfig = {};
  @Output() edit = new EventEmitter<void>();

  get serverTypeBadgeClass(): string {
    const type = this.model.type === 'OPEN_AI' ? 'openai' : this.model.type?.toLowerCase() ?? 'unknown';
    return `badge-${type.replace(/[^a-z]/g, '')}`;
  }

  get serverTypeLabel(): string {
    const labels: Record<string, string> = {
      'OLLAMA': 'Ollama',
      'OPEN_AI': 'OpenAI'
    };
    return labels[this.model.type ?? ''] ?? this.model.type ?? 'Unknown';
  }

  get hostFromUrl(): string {
    if (!this.model.apiBaseURL) return '';
    try {
      const url = new URL(this.model.apiBaseURL);
      return url.host;
    } catch {
      return this.model.apiBaseURL;
    }
  }

  onEdit(): void {
    this.edit.emit();
  }
}
