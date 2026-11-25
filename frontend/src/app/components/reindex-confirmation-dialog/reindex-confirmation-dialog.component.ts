import { Component, Output, EventEmitter } from '@angular/core';
import { DialogOverlayComponent } from '../dialog-overlay/dialog-overlay.component';

@Component({
  selector: 'reindex-confirmation-dialog',
  standalone: true,
  imports: [DialogOverlayComponent],
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
