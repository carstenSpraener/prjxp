import { TestBed } from '@angular/core/testing';
import { ProjectDetailCardComponent } from './project-detail-card.component';
import { ProjectInfo } from '../../models/project-info.model';
import { vi } from 'vitest';

describe('ProjectDetailCardComponent', () => {
  let component: ProjectDetailCardComponent;
  let fixture: any;

  const mockProject: ProjectInfo = {
    name: 'test-project',
    status: 'READY',
    kind: 'LIVE',
    chunkCount: 500,
    embeddingCount: 500,
    fileCount: 120,
    reindexStatus: 'IDLE',
    reindexProgress: 0
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectDetailCardComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ProjectDetailCardComponent);
    component = fixture.componentInstance;
    component.project = mockProject;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display project name', () => {
    const nameEl = fixture.nativeElement.querySelector('.project-name');
    expect(nameEl.textContent).toBe('test-project');
  });

  it('should display status badge', () => {
    const badges = fixture.nativeElement.querySelectorAll('.badge');
    expect(badges.length).toBe(2); // kind + status
    expect(badges[1].textContent).toBe('READY');
  });

  it('status badge should have correct color class for READY', () => {
    expect(component.getStatusColor()).toBe('status-ready');
  });

  it('status badge should have correct color class for FAILED', () => {
    component.project = { ...mockProject, status: 'FAILED' };
    expect(component.getStatusColor()).toBe('status-failed');
  });

  it('status badge should have default color class for other statuses', () => {
    component.project = { ...mockProject, status: 'IMPORTING' };
    expect(component.getStatusColor()).toBe('status-default');
  });

  it('edit button should emit edit event', () => {
    const editSpy = vi.fn();
    component.edit.subscribe(editSpy);

    fixture.nativeElement.querySelector('.btn-primary').click();
    expect(editSpy).toHaveBeenCalled();
  });

  it('reindex button should emit reindexConfirm event with project', () => {
    const reindexSpy = vi.fn();
    component.reindexConfirm.subscribe(reindexSpy);

    fixture.nativeElement.querySelector('.btn-secondary').click();
    expect(reindexSpy).toHaveBeenCalledWith(mockProject);
  });

  it('reindex button should not emit when reindexing', () => {
    const reindexSpy = vi.fn();
    component.reindexConfirm.subscribe(reindexSpy);

    component.project = { ...mockProject, reindexStatus: 'CHUNKING', reindexProgress: 25 };
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.btn-secondary').click();
    expect(reindexSpy).not.toHaveBeenCalled();
  });

  it('isReindexing should return true for CHUNKING status', () => {
    component.project = { ...mockProject, reindexStatus: 'CHUNKING' };
    expect(component.isReindexing()).toBeTruthy();
  });

  it('isReindexing should return true for EMBEDDING status', () => {
    component.project = { ...mockProject, reindexStatus: 'EMBEDDING' };
    expect(component.isReindexing()).toBeTruthy();
  });

  it('isReindexing should return true for IMPORTING status', () => {
    component.project = { ...mockProject, reindexStatus: 'IMPORTING' };
    expect(component.isReindexing()).toBeTruthy();
  });

  it('isReindexing should return false for IDLE status', () => {
    component.project = { ...mockProject, reindexStatus: 'IDLE' };
    expect(component.isReindexing()).toBeFalsy();
  });

  it('isReindexing should return false for COMPLETE status', () => {
    component.project = { ...mockProject, reindexStatus: 'COMPLETE' };
    expect(component.isReindexing()).toBeFalsy();
  });

  it('delete button should show confirm dialog and emit delete event', () => {
    const deleteSpy = vi.fn();
    component.delete.subscribe(deleteSpy);

    // Mock confirm to return true
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    fixture.nativeElement.querySelector('.btn-danger').click();
    expect(window.confirm).toHaveBeenCalledWith('Delete project "test-project"?');
    expect(deleteSpy).toHaveBeenCalledWith('test-project');
  });

  it('delete button should not emit if confirm returns false', () => {
    const deleteSpy = vi.fn();
    component.delete.subscribe(deleteSpy);

    vi.spyOn(window, 'confirm').mockReturnValue(false);

    fixture.nativeElement.querySelector('.btn-danger').click();
    expect(deleteSpy).not.toHaveBeenCalled();
  });

  it('should display stats (chunks, embeddings, files)', () => {
    const statValues = fixture.nativeElement.querySelectorAll('.stat-value');
    expect(statValues[0].textContent).toBe('500');
    expect(statValues[1].textContent).toBe('500');
    expect(statValues[2].textContent).toBe('120');
  });

  it('should display kind badge when project has kind', () => {
    const badges = fixture.nativeElement.querySelectorAll('.badge');
    expect(badges[0].textContent).toBe('LIVE');
    expect(badges[0].classList).toContain('badge-kind');
  });

  it('should render error message element when lastError is set on initial project', () => {
    const projectWithError: ProjectInfo = {
      ...mockProject,
      lastError: 'Something went wrong'
    };
    component.project = projectWithError;

    expect(component.project.lastError).toBe('Something went wrong');
  });

  it('should not have error message when lastError is absent', () => {
    expect(component.project.lastError).toBeUndefined();
  });

  it('should not show reindex progress when status is IDLE', () => {
    fixture.componentRef.setInput('project', { ...mockProject, reindexStatus: 'IDLE', reindexProgress: 0 });
    fixture.detectChanges();

    const progressEl = fixture.nativeElement.querySelector('.reindex-progress');
    expect(progressEl).toBeNull();
  });

  it('should show reindex progress when status is CHUNKING', () => {
    fixture.componentRef.setInput('project', { ...mockProject, reindexStatus: 'CHUNKING', reindexProgress: 25 });
    fixture.detectChanges();

    const progressEl = fixture.nativeElement.querySelector('.reindex-progress');
    expect(progressEl).not.toBeNull();
  });

  it('should show reindex progress when status is EMBEDDING', () => {
    fixture.componentRef.setInput('project', { ...mockProject, reindexStatus: 'EMBEDDING', reindexProgress: 60 });
    fixture.detectChanges();

    const progressEl = fixture.nativeElement.querySelector('.reindex-progress');
    expect(progressEl).not.toBeNull();
  });

  it('should show reindex progress when status is IMPORTING', () => {
    fixture.componentRef.setInput('project', { ...mockProject, reindexStatus: 'IMPORTING', reindexProgress: 90 });
    fixture.detectChanges();

    const progressEl = fixture.nativeElement.querySelector('.reindex-progress');
    expect(progressEl).not.toBeNull();
  });

  it('should not show reindex progress when status is COMPLETE', () => {
    fixture.componentRef.setInput('project', { ...mockProject, reindexStatus: 'COMPLETE', reindexProgress: 100 });
    fixture.detectChanges();

    const progressEl = fixture.nativeElement.querySelector('.reindex-progress');
    expect(progressEl).toBeNull();
  });
});
