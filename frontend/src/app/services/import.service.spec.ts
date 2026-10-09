import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ImportService } from './import.service';
import { ProjectInfo } from '../models/project-info.model';
import { LiveProjectCandidate, ProjectCreateRequest } from '../models/live-project-candidate.model';

describe('ImportService', () => {
  let service: ImportService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule]
    });
    service = TestBed.inject(ImportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  describe('uploadArchive', () => {
    it('should POST file to /api/projects/upload and return ProjectInfo', () => {
      const file = new File(['content'], 'test.tar.gz');
      const mockResponse: ProjectInfo = { name: 'test', status: 'READY' };

      service.uploadArchive(file).subscribe(result => {
        expect(result).toEqual(mockResponse);
      });

      const req = httpMock.expectOne('/api/projects/upload');
      expect(req.request.method).toBe('POST');
      expect(req.request.body instanceof FormData).toBe(true);
      req.flush(mockResponse);
    });
  });

  describe('scanLiveProjects', () => {
    it('should GET /api/live-scan and return LiveProjectCandidate[]', () => {
      const mockCandidates: LiveProjectCandidate[] = [
        { name: 'proj1', directory: '/path/to/proj1', hasConfigFile: true, languages: ['Java'] }
      ];

      service.scanLiveProjects().subscribe(result => {
        expect(result).toEqual(mockCandidates);
      });

      const req = httpMock.expectOne('/api/live-scan');
      expect(req.request.method).toBe('GET');
      req.flush(mockCandidates);
    });
  });

  describe('registerProject', () => {
    it('should POST ProjectCreateRequest to /api/projects and return ProjectInfo', () => {
      const request: ProjectCreateRequest = { name: 'new-proj', kind: 'LIVE', rootDir: '/path' };
      const mockResponse: ProjectInfo = { name: 'new-proj', status: 'IMPORTING' };

      service.registerProject(request).subscribe(result => {
        expect(result).toEqual(mockResponse);
      });

      const req = httpMock.expectOne('/api/projects');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockResponse);
    });
  });

  describe('importEmbeddings', () => {
    it('should POST file to /api/projects/{name}/importEmbeddings with observe: events', () => {
      const file = new File(['data'], 'embeddings.bin');
      const projectName = 'my-project';

      service.importEmbeddings(projectName, file).subscribe(() => {
        // just verify request was made
      });

      const req = httpMock.expectOne(`/api/projects/${projectName}/importEmbeddings`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body instanceof FormData).toBe(true);
      req.flush({});
    });
  });
});
