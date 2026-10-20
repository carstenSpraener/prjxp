import { Component, Output, EventEmitter } from '@angular/core';

@Component({
  selector: 'reindex-confirmation-dialog',
  standalone: true,
  templateUrl: './reindex-confirmation-dialog.component.html',
  styleUrl: './reindex-confirmation-dialog.component.css'
})
export class ReindexConfirmationDialogComponent {
  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  onConfirm(): void {
    this.confirmed.emit();
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
