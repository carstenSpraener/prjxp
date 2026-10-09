import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { DashboardService } from './dashboard.service';
import { SystemHealth } from '../models/system-health.model';
import { QueryStats } from '../models/query-stats.model';
import { ProjectInfo } from '../models/project-info.model';

describe('DashboardService', () => {
  let service: DashboardService;
  let httpMock: HttpTestingController;

  const mockHealth: SystemHealth = {
    cpuUsage: 0.45,
    totalMemory: 16000000000,
    usedMemory: 8000000000,
    freeDiskSpace: 500000000000,
    totalDiskSpace: 1000000000000,
    activeConnections: 42
  };

  const mockQueryStats: QueryStats = {
    totalQueries: 1000,
    avgResponseTimeMs: 45,
    p95ResponseTimeMs: 120,
    cacheHits: 750,
    cacheMisses: 250,
    recentQueries: []
  };

  const mockProjects: ProjectInfo[] = [
    { name: 'test-project', status: 'READY', chunkCount: 500, embeddingCount: 500 }
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [DashboardService]
    });

    service = TestBed.inject(DashboardService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('getSystemHealth() should GET /api/system/health', () => {
    service.getSystemHealth().subscribe(result => {
      expect(result).toEqual(mockHealth);
    });

    const req = httpMock.expectOne('/api/system/health');
    expect(req.request.method).toBe('GET');
    req.flush(mockHealth);
  });

  it('getQueryStats() should GET /api/system/query-stats', () => {
    service.getQueryStats().subscribe(result => {
      expect(result).toEqual(mockQueryStats);
    });

    const req = httpMock.expectOne('/api/system/query-stats');
    expect(req.request.method).toBe('GET');
    req.flush(mockQueryStats);
  });

  it('getProjectStats() should GET /api/tools/projects', () => {
    service.getProjectStats().subscribe(result => {
      expect(result).toEqual(mockProjects);
    });

    const req = httpMock.expectOne('/api/tools/projects');
    expect(req.request.method).toBe('GET');
    req.flush(mockProjects);
  });
});
