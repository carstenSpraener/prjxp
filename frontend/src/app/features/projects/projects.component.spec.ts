import { TestBed } from '@angular/core/testing';
import { ProjectsComponent } from './projects.component';
import { ProjectManagementService } from '../../services/project-management.service';
import { ProjectInfo } from '../../models/project-info.model';
import { PrjxpYamlConfig } from '../../models/prjxp-yaml-config.model';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ReactiveFormsModule } from '@angular/forms';

describe('ProjectsComponent', () => {
  let component: ProjectsComponent;
  let fixture: any;
  let mockService: any;

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
      getReindexStatus: vi.fn().mockReturnValue(of({ status: 'IDLE', progress: 0 }))
    };

    await TestBed.configureTestingModule({
      imports: [ProjectsComponent, HttpClientTestingModule, ReactiveFormsModule],
      providers: [
        { provide: ProjectManagementService, useValue: mockService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ProjectsComponent);
    component = fixture.componentInstance;
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
});
