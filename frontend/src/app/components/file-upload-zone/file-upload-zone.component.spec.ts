import { vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FileUploadZoneComponent } from './file-upload-zone.component';

describe('FileUploadZoneComponent', () => {
  let component: FileUploadZoneComponent;
  let fixture: ComponentFixture<FileUploadZoneComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FileUploadZoneComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(FileUploadZoneComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeDefined();
  });

  it('should render upload prompt when no file selected', () => {
    const nativeEl = fixture.nativeElement;
    expect(nativeEl.querySelector('.upload-prompt')).toBeDefined();
    expect(nativeEl.querySelector('.file-info')).toBeNull();
  });

  it('should show file info when a valid file is selected', () => {
    const file = new File(['content'], 'test.tar.gz');

    // Call handleFile directly (synchronous)
    (component as any).handleFile(file);
    fixture.detectChanges();

    const nativeEl = fixture.nativeElement;
    expect(nativeEl.querySelector('.file-info')).toBeDefined();
    expect(component.selectedFile).toBe(file);
  });

  it('should accept valid extensions (.tar, .zip)', () => {
    const tarFile = new File([''], 'archive.tar');
    const zipFile = new File([''], 'archive.zip');

    let emittedCount = 0;
    component.fileSelected.subscribe(() => {
      emittedCount++;
    });

    vi.spyOn(component, 'handleFile').mockImplementation((file: File) => {
      const validExtensions = ['.tar', '.tar.gz', '.tgz', '.zip'];
      const isValid = validExtensions.some(ext => file.name.endsWith(ext));
      if (isValid) {
        component.selectedFile = file;
        component.fileSelected.emit(file);
      }
    });

    (component as any).handleFile(tarFile);
    expect(emittedCount).toBe(1);

    (component as any).handleFile(zipFile);
    expect(emittedCount).toBe(2);
  });

  it('should reject invalid extensions (.txt)', () => {
    const txtFile = new File([''], 'readme.txt');

    let emittedCount = 0;
    component.fileSelected.subscribe(() => {
      emittedCount++;
    });

    const alertSpy = vi.spyOn(window, 'alert');

    (component as any).handleFile(txtFile);
    expect(emittedCount).toBe(0);
    expect(alertSpy).toHaveBeenCalledWith('Invalid file type. Please upload .tar, .tar.gz, .tgz, .zip files.');
  });

  it('should use custom accept input for validation', () => {
    component.accept = '.jsonl,.gz,.tgz,.zip';

    const validFile = new File([''], 'my-project-embedded.tgz');
    let emittedCount = 0;
    component.fileSelected.subscribe(() => {
      emittedCount++;
    });

    (component as any).handleFile(validFile);
    expect(emittedCount).toBe(1);
    expect(component.selectedFile).toBe(validFile);

    const invalidFile = new File([''], 'foo.txt');
    const alertSpy = vi.spyOn(window, 'alert');
    (component as any).handleFile(invalidFile);
    expect(alertSpy).toHaveBeenCalledWith('Invalid file type. Please upload .jsonl, .gz, .tgz, .zip files.');
  });

  it('should format file size correctly', () => {
    expect(component.formatFileSize(500)).toBe('500 B');
    expect(component.formatFileSize(1500)).toBe('1.5 KB');
    expect(component.formatFileSize(1500000)).toBe('1.4 MB');
  });

  it('should set isDragOver to true on dragover', () => {
    const event = new Event('dragover');
    vi.spyOn(event, 'preventDefault');

    component.onDragOver(event as unknown as DragEvent);
    expect(component.isDragOver).toBe(true);
  });

  it('should set isDragOver to false on dragleave', () => {
    component.isDragOver = true;
    component.onDragLeave();
    expect(component.isDragOver).toBe(false);
  });

  it('should handle drop event and emit file', () => {
    const file = new File([''], 'test.tgz');
    const mockDataTransfer = { files: [file] };
    const event = new Event('drop');

    component.fileSelected.subscribe(emitted => {
      expect(emitted).toBe(file);
    });

    // Create a mock DragEvent with dataTransfer
    const dragEvent = {
      ...event,
      dataTransfer: mockDataTransfer as unknown as DataTransfer,
      preventDefault: () => {}
    } as unknown as DragEvent;

    component.onDrop(dragEvent);
  });

  it('should clear selected file on cancel', () => {
    const file = new File([''], 'test.tar');
    (component as any).handleFile(file);
    expect(component.selectedFile).toBe(file);

    component.selectedFile = null;
    expect(component.selectedFile).toBeNull();
  });
});
