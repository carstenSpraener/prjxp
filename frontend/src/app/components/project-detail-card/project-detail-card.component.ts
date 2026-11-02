import { Component, Input, Output, EventEmitter } from '@angular/core';
import { ProjectInfo } from '../../models/project-info.model';
import { LiveUpdateIndicatorComponent } from '../live-update-indicator/live-update-indicator.component';

@Component({
  selector: 'project-detail-card',
  standalone: true,
  imports: [LiveUpdateIndicatorComponent],
  templateUrl: './project-detail-card.component.html',
  styleUrl: './project-detail-card.component.css'
})
export class ProjectDetailCardComponent {
  @Input() project!: ProjectInfo;
  @Output() edit = new EventEmitter<void>();
  @Output() reindexConfirm = new EventEmitter<ProjectInfo>();
  @Output() docpipe = new EventEmitter<void>();
  @Output() delete = new EventEmitter<string>();

  onEdit(): void {
    this.edit.emit();
  }

  onReindex(): void {
    if (!this.isReindexing()) {
      this.reindexConfirm.emit(this.project);
    }
  }

  onDocPipe(): void {
    if (!this.isReindexing()) {
      this.docpipe.emit();
    }
  }

  onDelete(): void {
    if (confirm(`Delete project "${this.project.name}"?`)) {
      this.delete.emit(this.project.name);
    }
  }

  isReindexing(): boolean {
    const status = this.project?.reindexStatus;
    return status === 'CHUNKING' || status === 'EMBEDDING' || status === 'IMPORTING' || status === 'DOCPIPE';
  }

  getStatusColor(): string {
    switch (this.project.status) {
      case 'READY': return 'status-ready';
      case 'FAILED': return 'status-failed';
      default: return 'status-default';
    }
  }

  freshnessLabel(): string | null {
    const lu = this.project.liveUpdate;
    if (!lu || !lu.lastGeneratedAt) { return null; }
    const d = new Date(lu.lastGeneratedAt);
    return `Index stand: ${d.toLocaleDateString()} ${d.toLocaleTimeString()}`;
  }
}
