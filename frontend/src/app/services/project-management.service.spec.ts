import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ProjectManagementService } from './project-management.service';
import { ProjectInfo } from '../models/project-info.model';
import { GithubOperation } from '../models/github-operation.model';
import { PrjxpYamlConfig, ProjectStats } from '../models/prjxp-yaml-config.model';

describe('ProjectManagementService', () => {
  let service: ProjectManagementService;
  let httpMock: HttpTestingController;

  const mockProject: ProjectInfo = {
    name: 'test-project',
    status: 'READY',
    kind: 'LIVE',
    chunkCount: 500,
    embeddingCount: 500,
    fileCount: 120
  };

  const mockProjects: ProjectInfo[] = [mockProject];

  const mockConfig: PrjxpYamlConfig = {
    name: 'new-project',
    rootDir: '.',
    jsonlFile: 'px-chunks.jsonl',
    chunoWhiteList: 'java,ts',
    tibedBatchSize: 32,
    embeddingsFile: undefined,
    tibedResetStore: false
  };

  const mockStats: ProjectStats = {
    name: 'test-project',
    chunkCount: 500,
    embeddingCount: 500,
    fileCount: 120,
    rootDir: '.',
    languages: ['java', 'ts']
  };

  const mockOperations: GithubOperation[] = [
    { key: 'https://github.com/foo/bar', status: 'SUCCESS', message: 'Imported bar', timestamp: 123 }
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProjectManagementService]
    });

    service = TestBed.inject(ProjectManagementService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('getProjects() should GET /api/tools/projects', () => {
    service.getProjects().subscribe(result => {
      expect(result).toEqual(mockProjects);
    });

    const req = httpMock.expectOne('/api/tools/projects');
    expect(req.request.method).toBe('GET');
    req.flush(mockProjects);
  });

  it('createProject() should POST /api/projects with body', () => {
    service.createProject(mockConfig).subscribe(result => {
      expect(result.name).toBe('new-project');
    });

    const req = httpMock.expectOne('/api/projects');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      name: 'new-project',
      kind: 'LIVE',
      rootDir: '.',
      chunoWhiteList: 'java,ts',
      tibedBatchSize: 32
    });
    req.flush({ name: 'new-project', status: 'READY' } as ProjectInfo);
  });

  it('updateProject() should PUT /api/projects/{name} with body', () => {
    const updates = { rootDir: '/new/path', chunoWhiteList: 'java' };
    service.updateProject('test-project', updates).subscribe(result => {
      expect(result.name).toBe('test-project');
    });

    const req = httpMock.expectOne('/api/projects/test-project');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(updates);
    req.flush({ name: 'test-project', status: 'READY' } as ProjectInfo);
  });

  it('deleteProject() should DELETE /api/projects/{name}', () => {
    service.deleteProject('test-project').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/test-project');
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('reindexProject() should POST /api/projects/{name}/reindex', () => {
    service.reindexProject('test-project').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/test-project/reindex');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('getProjectStats() should GET /api/projects/{name}/stats', () => {
    service.getProjectStats('test-project').subscribe(result => {
      expect(result).toEqual(mockStats);
    });

    const req = httpMock.expectOne('/api/projects/test-project/stats');
    expect(req.request.method).toBe('GET');
    req.flush(mockStats);
  });

  it('getReindexStatus() should GET /api/projects/{name}/reindex-status', () => {
    service.getReindexStatus('test-project').subscribe(result => {
      expect(result.status).toBe('CHUNKING');
      expect(result.progress).toBe(42);
    });

    const req = httpMock.expectOne('/api/projects/test-project/reindex-status');
    expect(req.request.method).toBe('GET');
    req.flush({ status: 'CHUNKING', progress: 42 });
  });

  it('embedDependencies() should POST /api/projects/{name}/embed-dependencies', () => {
    service.embedDependencies('test-project').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/test-project/embed-dependencies');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('reimportProject() should POST /api/projects/{name}/reimport', () => {
    service.reimportProject('test-project').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/test-project/reimport');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('importGithub() should POST /api/projects/import-github with url body and a null token', () => {
    service.importGithub('https://github.com/foo/bar.git').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/import-github');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ url: 'https://github.com/foo/bar.git', token: null });
    req.flush({});
  });

  it('importGithub() should include the token in the body when provided', () => {
    service.importGithub('https://github.com/foo/bar.git', 'ghp_abc123').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/import-github');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ url: 'https://github.com/foo/bar.git', token: 'ghp_abc123' });
    req.flush({});
  });

  it('importGithub() should send a null token for a blank token', () => {
    service.importGithub('https://github.com/foo/bar.git', '   ').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/import-github');
    expect(req.request.body).toEqual({ url: 'https://github.com/foo/bar.git', token: null });
    req.flush({});
  });

  it('updateGithub() should POST /api/projects/{name}/update-github', () => {
    service.updateGithub('test-project').subscribe(() => {
      // just verify no error
    });

    const req = httpMock.expectOne('/api/projects/test-project/update-github');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('getGithubOperations() should GET /api/projects/github-operations', () => {
    service.getGithubOperations().subscribe(result => {
      expect(result).toEqual(mockOperations);
    });

    const req = httpMock.expectOne('/api/projects/github-operations');
    expect(req.request.method).toBe('GET');
    req.flush(mockOperations);
  });
});
