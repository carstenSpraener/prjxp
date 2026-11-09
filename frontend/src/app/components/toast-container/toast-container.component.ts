import { Component } from '@angular/core';
import { ToastService } from '../../services/toast.service';

/**
 * Renders the {@link ToastService} list as a fixed stack (bottom-right, above the dialog
 * overlay). Each toast is a small glass panel with a type-colored icon + a close button.
 */
@Component({
  selector: 'toast-container',
  standalone: true,
  imports: [],
  templateUrl: './toast-container.component.html',
  styleUrl: './toast-container.component.css'
})
export class ToastContainerComponent {
  constructor(public toastService: ToastService) {}

  dismiss(id: number): void {
    this.toastService.dismiss(id);
  }
}
