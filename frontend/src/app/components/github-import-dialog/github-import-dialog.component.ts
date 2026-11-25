import { Component, Input, Output, EventEmitter } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { DialogOverlayComponent } from '../dialog-overlay/dialog-overlay.component';

@Component({
  selector: 'github-import-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, DialogOverlayComponent],
  templateUrl: './github-import-dialog.component.html',
  styleUrl: './github-import-dialog.component.css'
})
export class GithubImportDialogComponent {
  @Output() submit = new EventEmitter<{ url: string; token: string }>();
  @Output() close = new EventEmitter<void>();

  /** Operation feedback, driven by the parent (the dialog only renders it). */
  @Input() status: 'idle' | 'running' | 'success' | 'error' = 'idle';
  @Input() message: string | null = null;

  form: FormGroup;

  constructor(private fb: FormBuilder) {
    this.form = this.fb.group({
      url: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
      token: ['']   // optional — only needed for private repositories
    });
  }

  onImport(): void {
    if (this.form.valid) {
      this.submit.emit({
        url: this.form.get('url')!.value.trim(),
        token: this.form.get('token')!.value.trim()
      });
    }
  }

  onClose(): void {
    this.close.emit();
  }
}
