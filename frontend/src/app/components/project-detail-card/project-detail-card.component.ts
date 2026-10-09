import { Component, Input, Output, EventEmitter } from '@angular/core';
import { ProjectInfo } from '../../models/project-info.model';

@Component({
  selector: 'project-detail-card',
  standalone: true,
  templateUrl: './project-detail-card.component.html',
  styleUrl: './project-detail-card.component.css'
})
export class ProjectDetailCardComponent {
  @Input() project!: ProjectInfo;
  @Output() edit = new EventEmitter<void>();
  @Output() reindexConfirm = new EventEmitter<ProjectInfo>();
  @Output() delete = new EventEmitter<string>();

  onEdit(): void {
    this.edit.emit();
  }

  onReindex(): void {
    if (!this.isReindexing()) {
      this.reindexConfirm.emit(this.project);
    }
  }

  onDelete(): void {
    if (confirm(`Delete project "${this.project.name}"?`)) {
      this.delete.emit(this.project.name);
    }
  }

  isReindexing(): boolean {
    const status = this.project?.reindexStatus;
    return status === 'CHUNKING' || status === 'EMBEDDING';
  }

  getStatusColor(): string {
    switch (this.project.status) {
      case 'READY': return 'status-ready';
      case 'FAILED': return 'status-failed';
      default: return 'status-default';
    }
  }
}
