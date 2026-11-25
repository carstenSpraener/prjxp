import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  type: 'success' | 'error';
  message: string;
}

/**
 * Minimal toast feedback for async operations (e.g. GitHub updates). Transient by design:
 * each toast auto-dismisses after 5 s; the list is rendered by the toast-container component.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private toasts = signal<Toast[]>([]);
  private nextId = 1;

  readonly list = this.toasts.asReadonly();

  success(message: string): void {
    this.push('success', message);
  }

  error(message: string): void {
    this.push('error', message);
  }

  dismiss(id: number): void {
    this.toasts.update(t => t.filter(x => x.id !== id));
  }

  private push(type: 'success' | 'error', message: string): void {
    const id = this.nextId++;
    this.toasts.update(t => [...t, { id, type, message }]);
    setTimeout(() => this.dismiss(id), 5000);
  }
}
