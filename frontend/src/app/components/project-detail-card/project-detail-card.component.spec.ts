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
    expect(nameEl.textContent.trim()).toBe('test-project');
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

  it('should display freshness stat when liveUpdate.lastGeneratedAt is set', () => {
    fixture.componentRef.setInput('project', { ...mockProject, liveUpdate: { status: 'IDLE', lastGeneratedAt: 1700000000000 } });
    fixture.detectChanges();

    const labels = Array.from(fixture.nativeElement.querySelectorAll('.stat-label')).map((el: any) => el.textContent.trim());
    expect(labels).toContain('Freshness');
    const values = Array.from(fixture.nativeElement.querySelectorAll('.stat-value')).map((el: any) => el.textContent);
    expect(values.some((v: string) => v.includes('Index stand:'))).toBe(true);
  });

  it('should not display freshness stat when liveUpdate is absent', () => {
    const labels = Array.from(fixture.nativeElement.querySelectorAll('.stat-label')).map((el: any) => el.textContent.trim());
    expect(labels).not.toContain('Freshness');
  });

  it('should not display freshness stat when lastGeneratedAt is missing', () => {
    fixture.componentRef.setInput('project', { ...mockProject, liveUpdate: { status: 'IDLE' } });
    fixture.detectChanges();

    const labels = Array.from(fixture.nativeElement.querySelectorAll('.stat-label')).map((el: any) => el.textContent.trim());
    expect(labels).not.toContain('Freshness');
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

  // ------------------------------------------------------------------ Embed Deps / Reimport (Phase 07)

  it('embed deps button should render and be enabled when reindexStatus is IDLE', () => {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('.btn-secondary')) as HTMLButtonElement[];
    const embedBtn = buttons.find(b => b.textContent.includes('Embed Deps'));
    expect(embedBtn).toBeTruthy();
    expect(embedBtn!.disabled).toBe(false);
  });

  it('embed deps button should emit embedDeps when clicked', () => {
    const embedSpy = vi.fn();
    component.embedDeps.subscribe(embedSpy);

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('.btn-secondary')) as HTMLButtonElement[];
    buttons.find(b => b.textContent.includes('Embed Deps'))!.click();

    expect(embedSpy).toHaveBeenCalledTimes(1);
  });

  it('embed deps button should be disabled while reindexing (CHUNKING)', () => {
    // setInput (not direct assignment) so the template re-evaluates the [disabled] binding
    fixture.componentRef.setInput('project', { ...mockProject, reindexStatus: 'CHUNKING', reindexProgress: 25 });
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('.btn-secondary')) as HTMLButtonElement[];
    // 3rd secondary button in template order: Reindex, DocPipe, Embed Deps
    expect(buttons[2].disabled).toBe(true);
  });

  it('reimport button should render only for library projects (mavenDependency set)', () => {
    // setInput (not direct assignment) so the @if (isLibrary()) block re-evaluates
    fixture.componentRef.setInput('project', { ...mockProject, mavenDependency: 'org.example:lib:1.0.0' });
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some(b => b.textContent.includes('Reimport'))).toBe(true);
  });

  it('reimport button should be absent for normal projects (no mavenDependency)', () => {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some(b => b.textContent.includes('Reimport'))).toBe(false);
  });

  it('reimport button should emit reimport when confirm returns true', () => {
    // setInput (not direct assignment) so the @if (isLibrary()) block renders the button
    fixture.componentRef.setInput('project', { ...mockProject, mavenDependency: 'org.example:lib:1.0.0' });
    fixture.detectChanges();

    const reimportSpy = vi.fn();
    component.reimport.subscribe(reimportSpy);
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    buttons.find(b => b.textContent.includes('Reimport'))!.click();

    expect(window.confirm).toHaveBeenCalledWith('Re-import library "test-project"?');
    expect(reimportSpy).toHaveBeenCalledTimes(1);
  });

  it('reimport button should not emit when confirm returns false', () => {
    // setInput (not direct assignment) so the @if (isLibrary()) block renders the button
    fixture.componentRef.setInput('project', { ...mockProject, mavenDependency: 'org.example:lib:1.0.0' });
    fixture.detectChanges();

    const reimportSpy = vi.fn();
    component.reimport.subscribe(reimportSpy);
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    buttons.find(b => b.textContent.includes('Reimport'))!.click();

    expect(reimportSpy).not.toHaveBeenCalled();
  });

  it('isLibrary() should return true when mavenDependency is set', () => {
    component.project = { ...mockProject, mavenDependency: 'org.example:lib:1.0.0' };
    expect(component.isLibrary()).toBe(true);
  });

  it('isLibrary() should return false when mavenDependency is absent', () => {
    expect(component.isLibrary()).toBe(false);
  });

  // ------------------------------------------------------------------ GitHub badge / Update (Phase 04)

  it('github badge should render when gitUrl is set', () => {
    // setInput (not direct assignment) so the @if (isGithub()) block re-evaluates
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git' });
    fixture.detectChanges();

    const badges = Array.from(fixture.nativeElement.querySelectorAll('.badge')).map((el: any) => el.textContent);
    expect(badges.some((t: string) => t.includes('GitHub'))).toBe(true);
  });

  it('github badge should be absent when gitUrl is not set', () => {
    const badges = Array.from(fixture.nativeElement.querySelectorAll('.badge')).map((el: any) => el.textContent);
    expect(badges.some((t: string) => t.includes('GitHub'))).toBe(false);
  });

  it('update button should render only for github projects (gitUrl set)', () => {
    // setInput (not direct assignment) so the @if (isGithub()) block renders the button
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git' });
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some(b => b.textContent.includes('Update'))).toBe(true);
  });

  it('update button should be absent for non-github projects (no gitUrl)', () => {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some(b => b.textContent.includes('Update'))).toBe(false);
  });

  it('update button should emit updateGithub when confirm returns true', () => {
    // setInput (not direct assignment) so the @if (isGithub()) block renders the button
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git' });
    fixture.detectChanges();

    const updateSpy = vi.fn();
    component.updateGithub.subscribe(updateSpy);
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    buttons.find(b => b.textContent.includes('Update'))!.click();

    expect(window.confirm).toHaveBeenCalledWith('Update GitHub project "test-project"?');
    expect(updateSpy).toHaveBeenCalledTimes(1);
  });

  it('update button should not emit when confirm returns false', () => {
    // setInput (not direct assignment) so the @if (isGithub()) block renders the button
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git' });
    fixture.detectChanges();

    const updateSpy = vi.fn();
    component.updateGithub.subscribe(updateSpy);
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    buttons.find(b => b.textContent.includes('Update'))!.click();

    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('update button should be disabled while reindexing', () => {
    // setInput (not direct assignment) so the @if block renders the button and the [disabled] binding re-evaluates
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git', reindexStatus: 'CHUNKING', reindexProgress: 25 });
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const updateBtn = buttons.find(b => b.textContent.includes('Update'));
    expect(updateBtn).toBeTruthy();
    expect(updateBtn!.disabled).toBe(true);
  });

  it('isGithub() should return true when gitUrl is set', () => {
    component.project = { ...mockProject, gitUrl: 'https://github.com/foo/bar.git' };
    expect(component.isGithub()).toBe(true);
  });

  it('isGithub() should return false when gitUrl is absent', () => {
    expect(component.isGithub()).toBe(false);
  });

  // ------------------------------------------------------------------ Private badge (Phase 07)

  it('private badge should render when gitUrl is set and gitTokenPresent is true', () => {
    // setInput (not direct assignment) so the @if (isPrivate()) block re-evaluates
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git', gitTokenPresent: true });
    fixture.detectChanges();

    const badges = Array.from(fixture.nativeElement.querySelectorAll('.badge')).map((el: any) => el.textContent);
    expect(badges.some((t: string) => t.includes('Private'))).toBe(true);
  });

  it('private badge should be absent when gitTokenPresent is false', () => {
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git', gitTokenPresent: false });
    fixture.detectChanges();

    const badges = Array.from(fixture.nativeElement.querySelectorAll('.badge')).map((el: any) => el.textContent);
    expect(badges.some((t: string) => t.includes('Private'))).toBe(false);
  });

  it('private badge should be absent when gitTokenPresent is null', () => {
    fixture.componentRef.setInput('project', { ...mockProject, gitUrl: 'https://github.com/foo/bar.git', gitTokenPresent: null });
    fixture.detectChanges();

    const badges = Array.from(fixture.nativeElement.querySelectorAll('.badge')).map((el: any) => el.textContent);
    expect(badges.some((t: string) => t.includes('Private'))).toBe(false);
  });

  it('private badge should be absent for non-github projects even when gitTokenPresent is true', () => {
    fixture.componentRef.setInput('project', { ...mockProject, gitTokenPresent: true });
    fixture.detectChanges();

    const badges = Array.from(fixture.nativeElement.querySelectorAll('.badge')).map((el: any) => el.textContent);
    expect(badges.some((t: string) => t.includes('Private'))).toBe(false);
  });

  it('isPrivate() should return true only when gitTokenPresent is true', () => {
    component.project = { ...mockProject, gitTokenPresent: true };
    expect(component.isPrivate()).toBe(true);

    component.project = { ...mockProject, gitTokenPresent: false };
    expect(component.isPrivate()).toBe(false);

    component.project = { ...mockProject, gitTokenPresent: null };
    expect(component.isPrivate()).toBe(false);

    component.project = { ...mockProject };
    expect(component.isPrivate()).toBe(false);
  });
});
