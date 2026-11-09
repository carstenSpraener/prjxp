import { TestBed } from '@angular/core/testing';
import { GithubImportDialogComponent } from './github-import-dialog.component';
import { vi } from 'vitest';

describe('GithubImportDialogComponent', () => {
  let component: GithubImportDialogComponent;
  let fixture: any;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GithubImportDialogComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(GithubImportDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('form should contain a url control with an empty default', () => {
    expect(component.form.get('url')?.value).toBe('');
  });

  it('form should contain a token control with an empty default', () => {
    expect(component.form.get('token')?.value).toBe('');
  });

  it('form should be invalid when url is empty', () => {
    expect(component.form.valid).toBe(false);
  });

  it('form should be invalid for a non-http url', () => {
    component.form.patchValue({ url: 'not-a-url' });
    expect(component.form.valid).toBe(false);
  });

  it('form should be valid when an http(s) url is provided', () => {
    component.form.patchValue({ url: 'https://github.com/foo/bar.git' });
    expect(component.form.valid).toBe(true);
  });

  it('submit button should be disabled while the form is invalid', () => {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const submitBtn = buttons.find(b => b.textContent.trim() === 'Import');
    expect(submitBtn).toBeTruthy();
    expect(submitBtn!.disabled).toBe(true);
  });

  it('onImport() should emit submit with the trimmed url and an empty token when the form is valid', () => {
    const submitSpy = vi.fn();
    component.submit.subscribe(submitSpy);

    component.form.patchValue({ url: 'https://github.com/foo/bar.git  ' });
    component.onImport();

    expect(submitSpy).toHaveBeenCalledWith({ url: 'https://github.com/foo/bar.git', token: '' });
  });

  it('onImport() should emit submit with the trimmed token when one is provided', () => {
    const submitSpy = vi.fn();
    component.submit.subscribe(submitSpy);

    component.form.patchValue({ url: 'https://github.com/foo/bar.git', token: '  ghp_abc123  ' });
    component.onImport();

    expect(submitSpy).toHaveBeenCalledWith({ url: 'https://github.com/foo/bar.git', token: 'ghp_abc123' });
  });

  it('onImport() should not emit when the form is invalid', () => {
    const submitSpy = vi.fn();
    component.submit.subscribe(submitSpy);

    component.onImport();

    expect(submitSpy).not.toHaveBeenCalled();
  });

  it('onImport() should not emit when the url is blank, even with a token', () => {
    const submitSpy = vi.fn();
    component.submit.subscribe(submitSpy);

    component.form.patchValue({ url: '', token: 'ghp_abc123' });
    component.onImport();

    expect(submitSpy).not.toHaveBeenCalled();
  });

  // ------------------------------------------------------------------ Phase 07: token field

  it('token field should render as a masked (password) input', () => {
    const input = fixture.nativeElement.querySelector('#githubToken') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input.type).toBe('password');
  });

  it('token field should be optional — a valid url alone keeps the form valid', () => {
    component.form.patchValue({ url: 'https://github.com/foo/bar.git' });
    expect(component.form.valid).toBe(true);
  });

  it('onClose() should emit close event', () => {
    const closeSpy = vi.fn();
    component.close.subscribe(closeSpy);

    component.onClose();

    expect(closeSpy).toHaveBeenCalledTimes(1);
  });

  it('should show field error when url is invalid and touched', () => {
    component.form.get('url')?.markAsTouched();
    fixture.detectChanges();

    const errorEl = fixture.nativeElement.querySelector('.field-error');
    expect(errorEl).toBeTruthy();
    expect(errorEl.textContent).toContain('A repository URL is required');
  });

  it('should not show field error when url is valid', () => {
    component.form.patchValue({ url: 'https://github.com/foo/bar.git' });
    component.form.get('url')?.markAsTouched();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.field-error')).toBeFalsy();
  });

  // ------------------------------------------------------------------ Phase 05: status area
  // Note: inputs are set via setInput() (not direct assignment) — in this Angular version a
  // plain property mutation does not mark the view for check, so the template would not re-render.

  it('status area should be hidden for the default idle status', () => {
    expect(fixture.nativeElement.querySelector('.op-status')).toBeNull();
  });

  it('should show a running status area', () => {
    fixture.componentRef.setInput('status', 'running');
    fixture.detectChanges();

    const el = fixture.nativeElement.querySelector('.op-status');
    expect(el).toBeTruthy();
    expect(el.textContent).toContain('Importing');
  });

  it('should show a success status area with the message', () => {
    fixture.componentRef.setInput('status', 'success');
    fixture.componentRef.setInput('message', 'Imported bar');
    fixture.detectChanges();

    const el = fixture.nativeElement.querySelector('.op-status');
    expect(el).toBeTruthy();
    expect(el.textContent).toContain('Imported bar');
    expect(el.classList).toContain('op-status-success');
  });

  it('should show an error status area with the message', () => {
    fixture.componentRef.setInput('status', 'error');
    fixture.componentRef.setInput('message', 'git clone failed');
    fixture.detectChanges();

    const el = fixture.nativeElement.querySelector('.op-status');
    expect(el).toBeTruthy();
    expect(el.textContent).toContain('git clone failed');
    expect(el.classList).toContain('op-status-error');
  });

  it('Import button should be disabled while running even with a valid form', () => {
    component.form.patchValue({ url: 'https://github.com/foo/bar.git' });
    fixture.componentRef.setInput('status', 'running');
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const submitBtn = buttons.find(b => b.textContent.trim() === 'Import');
    expect(submitBtn!.disabled).toBe(true);
  });

  it('Import button should be enabled when idle and the form is valid', () => {
    component.form.patchValue({ url: 'https://github.com/foo/bar.git' });
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const submitBtn = buttons.find(b => b.textContent.trim() === 'Import');
    expect(submitBtn!.disabled).toBe(false);
  });
});
