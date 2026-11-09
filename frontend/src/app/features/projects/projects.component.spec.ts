import { TestBed } from '@angular/core/testing';
import { ProjectsComponent } from './projects.component';
import { ProjectManagementService } from '../../services/project-management.service';
import { ToastService } from '../../services/toast.service';
import { ProjectInfo } from '../../models/project-info.model';
import { PrjxpYamlConfig } from '../../models/prjxp-yaml-config.model';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ReactiveFormsModule } from '@angular/forms';

describe('ProjectsComponent', () => {
  let component: ProjectsComponent;
  let fixture: any;
  let mockService: any;
  let toastService: ToastService;

  const mockProjects: ProjectInfo[] = [
    { name: 'project-a', status: 'READY', chunkCount: 100, embeddingCount: 100, reindexStatus: 'IDLE', reindexProgress: 0 },
    { name: 'project-b', status: 'FAILED', chunkCount: 50, embeddingCount: 0, reindexStatus: 'IDLE', reindexProgress: 0 }
  ];

  beforeEach(async () => {
    mockService = {
      getProjects: vi.fn().mockReturnValue(of(mockProjects)),
      createProject: vi.fn().mockReturnValue(of({ name: 'new-project', status: 'READY' })),
      updateProject: vi.fn().mockReturnValue(of({ name: 'project-a', status: 'READY' })),
      deleteProject: vi.fn().mockReturnValue(of({})),
      reindexProject: vi.fn().mockReturnValue(of({})),
      embedDependencies: vi.fn().mockReturnValue(of({})),
      reimportProject: vi.fn().mockReturnValue(of({})),
      importGithub: vi.fn().mockReturnValue(of({})),
      updateGithub: vi.fn().mockReturnValue(of({})),
      getReindexStatus: vi.fn().mockReturnValue(of({ status: 'IDLE', progress: 0 })),
      getGithubOperations: vi.fn().mockReturnValue(of([]))
    };

    await TestBed.configureTestingModule({
      imports: [ProjectsComponent, HttpClientTestingModule, ReactiveFormsModule],
      providers: [
        { provide: ProjectManagementService, useValue: mockService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ProjectsComponent);
    component = fixture.componentInstance;
    toastService = TestBed.inject(ToastService);   // real — the component shows toasts through it
    // Component loads projects in ngOnInit, so we need to handle that
    fixture.detectChanges();
  });

  afterEach(() => {
    component.ngOnDestroy(); // clean up any intervals
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load projects on init', () => {
    expect(mockService.getProjects).toHaveBeenCalled();
    expect(component.projects().length).toBe(2);
  });

  it('selectProject() should update selectedProject signal', () => {
    component.selectProject(mockProjects[0]);
    expect(component.selectedProject()?.name).toBe('project-a');
  });

  it('filteredProjects() should return all projects when filter is empty', () => {
    component.searchFilter.set('');
    expect(component.filteredProjects().length).toBe(2);
  });

  it('filteredProjects() should filter by searchFilter', () => {
    component.searchFilter.set('project-a');
    const filtered = component.filteredProjects();
    expect(filtered.length).toBe(1);
    expect(filtered[0].name).toBe('project-a');
  });

  it('filteredProjects() should be case-insensitive', () => {
    component.searchFilter.set('PROJECT-A');
    const filtered = component.filteredProjects();
    expect(filtered.length).toBe(1);
  });

  it('onYamlSave() should call createProject for new project', () => {
    const newConfig: PrjxpYamlConfig = {
      name: 'new-project',
      rootDir: '.',
      chunoWhiteList: 'java,ts',
      tibedBatchSize: 32
    };

    component.onYamlSave(newConfig);
    expect(mockService.createProject).toHaveBeenCalledWith(newConfig);
  });

  it('onYamlSave() should call updateProject for existing project', () => {
    const updateConfig: PrjxpYamlConfig = {
      name: 'project-a',
      rootDir: '/new/path',
      chunoWhiteList: 'java',
      tibedBatchSize: 64
    };

    component.onYamlSave(updateConfig);
    expect(mockService.updateProject).toHaveBeenCalledWith('project-a', {
      rootDir: '/new/path',
      chunoWhiteList: 'java',
      tibedBatchSize: 64,
      prompt: null
    });
  });

  it('onDeleteProject() should call deleteProject and reload projects', () => {
    mockService.deleteProject.mockReturnValue(of({}));
    mockService.getProjects.mockReturnValue(of([{ name: 'project-b', status: 'FAILED' }]));

    component.selectedProjectName.set('project-a');
    component.onDeleteProject('project-a');

    expect(mockService.deleteProject).toHaveBeenCalledWith('project-a');
    expect(mockService.getProjects).toHaveBeenCalled();
    // selectedProject should be cleared since it was the deleted project
    expect(component.selectedProject()).toBeNull();
  });

  // ------------------------------------------------------------------ Reindex confirmation flow

  it('onReindexConfirm() should show the confirmation dialog', () => {
    component.onReindexConfirm(mockProjects[0]);

    expect(component.showReindexConfirm()).toBe(true);
    expect(component.reindexProjectName()).toBe('project-a');
  });

  it('onReindexConfirmed() should call reindexProject and start polling', () => {
    component.reindexProjectName.set('project-a');

    // Mock getReindexStatus to return COMPLETE on first call
    mockService.getReindexStatus.mockReturnValue(of({ status: 'COMPLETE', progress: 100 }));

    component.onReindexConfirmed();

    expect(mockService.reindexProject).toHaveBeenCalledWith('project-a');
    expect(component.showReindexConfirm()).toBe(false);
  });

  it('onReindexCancelled() should hide the confirmation dialog', () => {
    component.reindexProjectName.set('project-a');
    component.showReindexConfirm.set(true);

    component.onReindexCancelled();

    expect(component.showReindexConfirm()).toBe(false);
    expect(component.reindexProjectName()).toBeNull(); // name is cleared on cancel
  });

  it('startPolling() should poll for reindex status', () => {
    vi.useFakeTimers();
    try {
      let callCount = 0;
      mockService.getReindexStatus.mockImplementation(() => {
        callCount++;
        if (callCount >= 2) {
          return of({ status: 'COMPLETE', progress: 100 });
        }
        return of({ status: 'CHUNKING', progress: 25 });
      });

      component.startPolling('project-a');

      // Advance past two poll intervals (2s each)
      vi.advanceTimersByTime(2000);
      vi.advanceTimersByTime(2000);

      expect(mockService.getReindexStatus).toHaveBeenCalledTimes(2);
      // Second poll returned COMPLETE, so polling should have stopped
      component.stopPolling();
    } finally {
      vi.useRealTimers();
    }
  });

  it('stopPolling() should clear the polling interval', () => {
    vi.useFakeTimers();
    try {
      mockService.getReindexStatus.mockReturnValue(of({ status: 'CHUNKING', progress: 25 }));

      component.startPolling('project-a');
      vi.advanceTimersByTime(2000); // one poll

      const callCount = (mockService.getReindexStatus as any).mock.calls.length;
      expect(callCount).toBe(1);

      component.stopPolling();
      vi.advanceTimersByTime(10000); // well past the interval

      const newCallCount = (mockService.getReindexStatus as any).mock.calls.length;
      expect(newCallCount).toBe(callCount); // no new calls after stop
    } finally {
      vi.useRealTimers();
    }
  });

  // ------------------------------------------------------------------ Reindex progress in the list

  it('isReindexActive() should return true for active reindex statuses', () => {
    expect(component.isReindexActive({ name: 'x', status: 'READY', reindexStatus: 'CHUNKING' })).toBe(true);
    expect(component.isReindexActive({ name: 'x', status: 'READY', reindexStatus: 'EMBEDDING' })).toBe(true);
    expect(component.isReindexActive({ name: 'x', status: 'READY', reindexStatus: 'IMPORTING' })).toBe(true);
  });

  it('isReindexActive() should return false for idle/finished statuses', () => {
    expect(component.isReindexActive({ name: 'x', status: 'READY', reindexStatus: 'IDLE' })).toBe(false);
    expect(component.isReindexActive({ name: 'x', status: 'READY', reindexStatus: 'COMPLETE' })).toBe(false);
    expect(component.isReindexActive({ name: 'x', status: 'READY', reindexStatus: 'FAILED' })).toBe(false);
    expect(component.isReindexActive({ name: 'x', status: 'READY' })).toBe(false);
  });

  it('should render a progress bar in the list for projects with an active reindex', () => {
    component.projects.set([{ name: 'project-a', status: 'READY', reindexStatus: 'EMBEDDING', reindexProgress: 42 }]);
    fixture.detectChanges();

    const fill = fixture.nativeElement.querySelector('.project-item .progress-bar-fill');
    expect(fill).not.toBeNull();
    expect(fill.style.width).toBe('42%');
  });

  it('should not render a progress bar in the list for idle projects', () => {
    component.projects.set([{ name: 'project-a', status: 'READY', reindexStatus: 'IDLE', reindexProgress: 0 }]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.project-item .progress-bar-fill')).toBeNull();
  });

  it('loadProjects() should resume polling for projects with an active reindex', () => {
    vi.useFakeTimers();
    try {
      mockService.getProjects.mockReturnValue(of([{ name: 'project-a', status: 'READY', reindexStatus: 'CHUNKING', reindexProgress: 5 }]));

      component.loadProjects();
      vi.advanceTimersByTime(2000);

      expect(mockService.getReindexStatus).toHaveBeenCalledWith('project-a');
    } finally {
      vi.useRealTimers();
    }
  });

  // ------------------------------------------------------------------ Editor flow

  it('onCreateProject() should clear editingProject and show editor', () => {
    component.editingProjectName.set('project-a');
    component.onCreateProject();

    expect(component.editingProject()).toBeNull();
    expect(component.showEditor()).toBe(true);
  });

  it('onEditProject() should set editingProject and show editor', () => {
    component.selectProject(mockProjects[0]);

    // The current onEditProject uses selectedProject, not a parameter
    component.onEditProject();

    expect(component.editingProject()?.name).toBe('project-a');
    expect(component.showEditor()).toBe(true);
  });

  it('onYamlClose() should hide editor', () => {
    component.showEditor.set(true);
    component.onYamlClose();

    expect(component.showEditor()).toBe(false);
  });

  it('getStatusBadgeClass() should return correct class for READY', () => {
    expect(component.getStatusBadgeClass('READY')).toBe('status-ready');
  });

  it('getStatusBadgeClass() should return correct class for FAILED', () => {
    expect(component.getStatusBadgeClass('FAILED')).toBe('status-failed');
  });

  it('getStatusBadgeClass() should return default class for other statuses', () => {
    expect(component.getStatusBadgeClass('IMPORTING')).toBe('status-default');
  });

  it('ngOnDestroy() should stop polling', () => {
    vi.useFakeTimers();
    try {
      mockService.getReindexStatus.mockReturnValue(of({ status: 'CHUNKING', progress: 25 }));

      component.startPolling('project-a');
      vi.advanceTimersByTime(2000); // one poll

      const callCount = (mockService.getReindexStatus as any).mock.calls.length;
      expect(callCount).toBe(1);

      component.ngOnDestroy();
      vi.advanceTimersByTime(10000); // well past the interval

      const newCallCount = (mockService.getReindexStatus as any).mock.calls.length;
      expect(newCallCount).toBe(callCount); // no new calls after destroy
    } finally {
      vi.useRealTimers();
    }
  });

  // ------------------------------------------------------------------ Embed Deps / Reimport flow (Phase 07)

  it('onEmbedDeps() should call embedDependencies for the selected project and reload the list', () => {
    component.selectProject(mockProjects[0]);
    const loadSpy = vi.spyOn(component, 'loadProjects');

    component.onEmbedDeps();

    expect(mockService.embedDependencies).toHaveBeenCalledWith('project-a');
    expect(loadSpy).toHaveBeenCalled();
  });

  it('onReimport() should call reimportProject for the selected project and reload the list', () => {
    component.selectProject(mockProjects[1]);
    const loadSpy = vi.spyOn(component, 'loadProjects');

    component.onReimport();

    expect(mockService.reimportProject).toHaveBeenCalledWith('project-b');
    expect(loadSpy).toHaveBeenCalled();
  });

  it('onEmbedDeps() should do nothing when no project is selected', () => {
    component.selectedProjectName.set(null);
    component.onEmbedDeps();
    expect(mockService.embedDependencies).not.toHaveBeenCalled();
  });

  it('onReimport() should do nothing when no project is selected', () => {
    component.selectedProjectName.set(null);
    component.onReimport();
    expect(mockService.reimportProject).not.toHaveBeenCalled();
  });

  it('onYamlSave() should include embedDependencies in the update request (round-trip)', () => {
    const updateConfig: PrjxpYamlConfig = {
      name: 'project-a',
      embedDependencies: true
    };

    component.onYamlSave(updateConfig);

    expect(mockService.updateProject).toHaveBeenCalledWith(
      'project-a',
      expect.objectContaining({ embedDependencies: true })
    );
  });

  // ------------------------------------------------------------------ GitHub import / update flow (Phase 04)

  it('onImportGithubClick() should open the import dialog', () => {
    component.onImportGithubClick();
    expect(component.showGithubImport()).toBe(true);
  });

  it('onImportGithub() should call importGithub, keep the dialog open and set running', () => {
    component.showGithubImport.set(true);
    const loadSpy = vi.spyOn(component, 'loadProjects');

    component.onImportGithub({ url: 'https://github.com/foo/bar.git', token: '' });

    expect(mockService.importGithub).toHaveBeenCalledWith('https://github.com/foo/bar.git', '');
    expect(component.showGithubImport()).toBe(true);   // stays open for the status feedback
    expect(component.dialogStatus()).toBe('running');
    expect(loadSpy).toHaveBeenCalled();
  });

  it('onImportGithub() should pass the token to importGithub when provided', () => {
    component.showGithubImport.set(true);

    component.onImportGithub({ url: 'https://github.com/foo/bar.git', token: 'ghp_abc123' });

    expect(mockService.importGithub).toHaveBeenCalledWith('https://github.com/foo/bar.git', 'ghp_abc123');
  });

  it('onImportGithub() should show success and auto-close the dialog when the poll resolves', () => {
    vi.useFakeTimers();
    try {
      component.showGithubImport.set(true);
      mockService.getGithubOperations.mockReturnValue(of([
        { key: 'https://github.com/foo/bar.git', status: 'SUCCESS', message: 'Imported bar', timestamp: 1 }
      ]));

      component.onImportGithub({ url: 'https://github.com/foo/bar.git', token: '' });
      vi.advanceTimersByTime(2000);   // one poll

      expect(component.dialogStatus()).toBe('success');
      expect(component.dialogMessage()).toBe('Imported bar');
      expect(component.showGithubImport()).toBe(true);   // still open, about to auto-close

      vi.advanceTimersByTime(1500);   // the auto-close delay

      expect(component.showGithubImport()).toBe(false);
      expect(component.dialogStatus()).toBe('idle');
      expect(component.dialogMessage()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('onImportGithub() should show the error and keep the dialog open when the poll fails', () => {
    vi.useFakeTimers();
    try {
      component.showGithubImport.set(true);
      mockService.getGithubOperations.mockReturnValue(of([
        { key: 'https://github.com/foo/bar.git', status: 'FAILED', message: 'git clone exited with code 128', timestamp: 1 }
      ]));

      component.onImportGithub({ url: 'https://github.com/foo/bar.git', token: '' });
      vi.advanceTimersByTime(2000);   // one poll

      expect(component.dialogStatus()).toBe('error');
      expect(component.dialogMessage()).toBe('git clone exited with code 128');
      expect(component.showGithubImport()).toBe(true);   // stays open — retryable
    } finally {
      vi.useRealTimers();
    }
  });

  it('onImportGithub() should show an error when the import request itself fails', () => {
    component.showGithubImport.set(true);
    mockService.importGithub.mockReturnValue(throwError(() => new Error('boom')));

    component.onImportGithub({ url: 'https://github.com/foo/bar.git', token: '' });

    expect(component.dialogStatus()).toBe('error');
    expect(component.dialogMessage()).toContain('boom');
    expect(component.showGithubImport()).toBe(true);   // stays open — retryable
  });

  it('onGithubImportClose() should hide the dialog, reset the status and stop the poll', () => {
    vi.useFakeTimers();
    try {
      component.showGithubImport.set(true);
      mockService.getGithubOperations.mockReturnValue(of([]));   // still PENDING

      component.onImportGithub({ url: 'https://github.com/foo/bar.git', token: '' });
      vi.advanceTimersByTime(2000);   // one poll
      const callsAfterOnePoll = (mockService.getGithubOperations as any).mock.calls.length;
      expect(callsAfterOnePoll).toBe(1);

      component.onGithubImportClose();

      expect(component.showGithubImport()).toBe(false);
      expect(component.dialogStatus()).toBe('idle');
      expect(component.dialogMessage()).toBeNull();

      vi.advanceTimersByTime(10000);   // well past the interval
      expect((mockService.getGithubOperations as any).mock.calls.length).toBe(callsAfterOnePoll);   // no new polls
    } finally {
      vi.useRealTimers();
    }
  });

  it('onUpdateGithub() should call updateGithub for the selected project and reload the list', () => {
    component.selectProject(mockProjects[0]);
    const loadSpy = vi.spyOn(component, 'loadProjects');

    component.onUpdateGithub();

    expect(mockService.updateGithub).toHaveBeenCalledWith('project-a');
    expect(loadSpy).toHaveBeenCalled();
  });

  it('onUpdateGithub() should do nothing when no project is selected', () => {
    component.selectedProjectName.set(null);
    component.onUpdateGithub();
    expect(mockService.updateGithub).not.toHaveBeenCalled();
  });

  it('onUpdateGithub() should show a success toast when the poll resolves successfully', () => {
    vi.useFakeTimers();
    try {
      component.selectProject(mockProjects[0]);
      mockService.getGithubOperations.mockReturnValue(of([
        { key: 'project-a', status: 'SUCCESS', message: 'Updated project-a', timestamp: 1 }
      ]));

      component.onUpdateGithub();
      vi.advanceTimersByTime(2000);   // one poll

      const toasts = toastService.list();
      expect(toasts).toHaveLength(1);
      expect(toasts[0].type).toBe('success');
      expect(toasts[0].message).toBe('Updated project-a');
    } finally {
      vi.useRealTimers();
    }
  });

  it('onUpdateGithub() should show an error toast when the poll fails', () => {
    vi.useFakeTimers();
    try {
      component.selectProject(mockProjects[0]);
      mockService.getGithubOperations.mockReturnValue(of([
        { key: 'project-a', status: 'FAILED', message: 'git pull exited with code 1', timestamp: 1 }
      ]));

      component.onUpdateGithub();
      vi.advanceTimersByTime(2000);   // one poll

      const toasts = toastService.list();
      expect(toasts).toHaveLength(1);
      expect(toasts[0].type).toBe('error');
      expect(toasts[0].message).toBe('git pull exited with code 1');
    } finally {
      vi.useRealTimers();
    }
  });

  // ------------------------------------------------------------------ Phase 05: github operation polling

  it('pollGithubOperation() should call onDone when the operation resolves', () => {
    vi.useFakeTimers();
    try {
      const onDone = vi.fn();
      mockService.getGithubOperations.mockReturnValue(of([
        { key: 'project-a', status: 'SUCCESS', message: 'Updated project-a', timestamp: 1 }
      ]));

      component.pollGithubOperation('project-a', onDone);
      vi.advanceTimersByTime(2000);   // one poll

      expect(onDone).toHaveBeenCalledWith(true, 'Updated project-a');
    } finally {
      vi.useRealTimers();
    }
  });

  it('pollGithubOperation() should keep polling while the operation is still pending', () => {
    vi.useFakeTimers();
    try {
      const onDone = vi.fn();
      mockService.getGithubOperations.mockReturnValue(of([
        { key: 'project-a', status: 'PENDING', message: null, timestamp: 1 }
      ]));

      component.pollGithubOperation('project-a', onDone);
      vi.advanceTimersByTime(6000);   // three polls

      expect(mockService.getGithubOperations).toHaveBeenCalledTimes(3);
      expect(onDone).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('pollGithubOperation() should keep polling when the poll request itself fails', () => {
    vi.useFakeTimers();
    try {
      const onDone = vi.fn();
      mockService.getGithubOperations.mockReturnValue(throwError(() => new Error('network')));

      component.pollGithubOperation('project-a', onDone);
      vi.advanceTimersByTime(6000);   // three failed polls

      expect(mockService.getGithubOperations).toHaveBeenCalledTimes(3);
      expect(onDone).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('pollGithubOperation() should call onDone with a timeout after the deadline', () => {
    vi.useFakeTimers();
    try {
      const onDone = vi.fn();
      mockService.getGithubOperations.mockReturnValue(of([]));   // the op never resolves

      component.pollGithubOperation('project-a', onDone);
      vi.advanceTimersByTime(305_000);   // well past the 300 s deadline

      expect(onDone).toHaveBeenCalledWith(false, 'Timed out waiting for the GitHub operation');
    } finally {
      vi.useRealTimers();
    }
  });
});
