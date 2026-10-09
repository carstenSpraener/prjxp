import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PrjxpApiService } from './prjxp-api.service';
import { ProjectInfo } from '../models/project-info.model';

describe('PrjxpApiService', () => {
  let service: PrjxpApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        PrjxpApiService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(PrjxpApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify(); // Assert no outstanding requests
  });

  it('should be created', () => {
    expect(service).toBeDefined();
  });

  it('ping() should call GET /api/tools/ping', () => {
    service.ping().subscribe(result => {
      expect(result).toBe('pong');
    });

    const req = httpMock.expectOne('/api/tools/ping');
    expect(req.request.method).toBe('GET');
    req.flush('pong');
  });

  it('getProjects() should call GET /api/tools/projects', () => {
    const mockProjects: ProjectInfo[] = [
      { name: 'test', status: 'READY', lastError: undefined }
    ];

    service.getProjects().subscribe(projects => {
      expect(projects).toEqual(mockProjects);
    });

    const req = httpMock.expectOne('/api/tools/projects');
    expect(req.request.method).toBe('GET');
    req.flush(mockProjects);
  });

  it('deleteProject() should call DELETE /api/projects/{name}', () => {
    service.deleteProject('test-project').subscribe();

    const req = httpMock.expectOne('/api/projects/test-project');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('reindexProject() should call POST /api/projects/{name}/reindex', () => {
    service.reindexProject('test-project').subscribe();

    const req = httpMock.expectOne('/api/projects/test-project/reindex');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });

  it('importEmbeddings() should send FormData to /api/projects/{name}/importEmbeddings', () => {
    const mockFile = new File(['content'], 'test.jsonl');

    service.importEmbeddings('test-project', mockFile).subscribe();

    const req = httpMock.expectOne('/api/projects/test-project/importEmbeddings');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    req.flush(null);
  });

  it('vectorSearch() should call GET /api/tools/vectorSearch with params', () => {
    service.vectorSearch('test query', 'my-project', 10).subscribe();

    const req = httpMock.expectOne(r => r.url.includes('/api/tools/vectorSearch'));
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('vectorSearch() without project should not include project param', () => {
    service.vectorSearch('test query').subscribe();

    const req = httpMock.expectOne(r => r.url.includes('/api/tools/vectorSearch'));
    expect(req.request.params.get('project')).toBeNull();
    req.flush([]);
  });

  it('grepSearch() should call GET /api/tools/grep with params', () => {
    service.grepSearch('test pattern').subscribe();

    const req = httpMock.expectOne(r => r.url.includes('/api/tools/grep'));
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
