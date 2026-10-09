import { Component, Input, Output, EventEmitter } from '@angular/core';

@Component({
  selector: 'file-upload-zone',
  standalone: true,
  templateUrl: './file-upload-zone.component.html',
  styleUrl: './file-upload-zone.component.css'
})
export class FileUploadZoneComponent {
  @Input() accept: string = '.tar,.tar.gz,.tgz,.zip';
  @Output() fileSelected = new EventEmitter<File>();

  isDragOver = false;
  selectedFile: File | null = null;

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver = true;
  }

  onDragLeave(): void {
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver = false;

    if (event.dataTransfer?.files?.length) {
      this.handleFile(event.dataTransfer.files[0]);
    }
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) {
      this.handleFile(input.files[0]);
    }
  }

  private handleFile(file: File): void {
    const validExtensions = this.accept.split(',');
    const isValid = validExtensions.some(ext => file.name.endsWith(ext.trim()));

    if (isValid) {
      this.selectedFile = file;
      this.fileSelected.emit(file);
    } else {
      alert(`Invalid file type. Please upload ${validExtensions.map(e => e.trim()).join(', ')} files.`);
    }
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
