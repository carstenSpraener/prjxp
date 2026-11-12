import { TestBed } from '@angular/core/testing';
import { DashboardComponent } from './dashboard.component';
import { DashboardService } from '../../services/dashboard.service';
import { MetricCardComponent } from '../../components/metric-card/metric-card.component';
import { ChartPanelComponent } from '../../components/chart-panel/chart-panel.component';
import { SystemHealth } from '../../models/system-health.model';
import { QueryStats } from '../../models/query-stats.model';
import { ProjectInfo } from '../../models/project-info.model';
import { of, Subject, throwError } from 'rxjs';
import { vi } from 'vitest';
import { SetupService } from '../../services/setup.service';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: any;

  beforeEach(async () => {
    // Mock DashboardService to prevent HTTP calls during component creation
    const mockDashboardService = {
      pollHealth: vi.fn().mockReturnValue(of({} as SystemHealth)),
      pollQueryStats: vi.fn().mockReturnValue(of({} as QueryStats)),
      getProjectStats: vi.fn().mockReturnValue(of([])),
      pollProjects: vi.fn().mockReturnValue(of([] as ProjectInfo[]))
    };

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: DashboardService, useValue: mockDashboardService },
        provideRouter([]),
        {provide: SetupService, useValue: {status: signal(null), error: signal(null), loading: signal(false), getStatus: () => of(null)}}
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
  it('renders populated health, query summary and project rows', () => {
    const service = TestBed.inject(DashboardService);
    vi.mocked(service.pollHealth).mockReturnValue(of({cpuUsage: .2, totalMemory: 16, usedMemory: 8,
      freeDiskSpace: 10, totalDiskSpace: 20, activeConnections: 2}));
    vi.mocked(service.pollQueryStats).mockReturnValue(of({totalQueries: 9, avgResponseTimeMs: 12,
      p95ResponseTimeMs: 22, cacheHits: 5, cacheMisses: 4, recentQueries: []}));
    vi.mocked(service.pollProjects).mockReturnValue(of([{name: 'demo', status: 'READY', chunkCount: 3,
      embeddingCount: 3, fileCount: 1, liveUpdate: {status: 'IDLE'}}]));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Total Queries');
    expect(fixture.nativeElement.textContent).toContain('demo');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
    expect(component.queryTimesData()[0].value).toBe(12);
  });
  it('renders loading states and tolerates polling failures', () => {
    const service = TestBed.inject(DashboardService);
    vi.mocked(service.pollHealth).mockReturnValue(new Subject<SystemHealth>());
    vi.mocked(service.pollProjects).mockReturnValue(new Subject<ProjectInfo[]>());
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Loading system health');
    expect(fixture.nativeElement.textContent).toContain('Loading projects');
    vi.mocked(service.pollHealth).mockReturnValue(throwError(() => ({})));
    vi.mocked(service.pollQueryStats).mockReturnValue(throwError(() => ({})));
    vi.mocked(service.pollProjects).mockReturnValue(throwError(() => ({})));
    component.ngOnInit();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No projects found');
  });

  it('ngOnInit should set up observables', () => {
    component.ngOnInit();

    // Verify the service methods were called
    const dashboardService = TestBed.inject(DashboardService);
    expect(dashboardService.pollHealth).toHaveBeenCalled();
    expect(dashboardService.pollQueryStats).toHaveBeenCalled();
    expect(dashboardService.pollProjects).toHaveBeenCalled();
  });

  it('should render metric cards', () => {
    fixture.detectChanges();

    const metricCards = fixture.nativeElement.querySelectorAll('metric-card');
    expect(metricCards.length).toBe(4); // CPU, Memory, Disk, Connections
  });

  it('should render chart panels', () => {
    fixture.detectChanges();

    const chartPanels = fixture.nativeElement.querySelectorAll('chart-panel');
    expect(chartPanels.length).toBe(2); // Response Times, Cache Performance
  });

  it('should render project table section', () => {
    fixture.detectChanges();

    // The projects section should exist (even if empty)
    const sections = fixture.nativeElement.querySelectorAll('.dashboard-section');
    expect(sections.length).toBe(3); // System Health, Query Stats, Projects
  });

  it('queryTimesData should have sample data after ngOnInit', () => {
    component.ngOnInit();
    // The AsyncPipe subscribes during change detection, which triggers the tap()
    // that populates the chart data signals.
    fixture.detectChanges();

    expect(component.queryTimesData().length).toBe(2);
    expect(component.queryTimesData()[0].label).toBe('Avg');
    expect(component.queryTimesData()[1].label).toBe('P95');
  });

  it('cacheData should have sample data after ngOnInit', () => {
    component.ngOnInit();
    // The AsyncPipe subscribes during change detection, which triggers the tap()
    // that populates the chart data signals.
    fixture.detectChanges();

    expect(component.cacheData().length).toBe(2);
    expect(component.cacheData()[0].label).toBe('Hits');
    expect(component.cacheData()[1].label).toBe('Misses');
  });

  it('should have dashboard title with neon glow', () => {
    fixture.detectChanges();

    const title = fixture.nativeElement.querySelector('h1');
    expect(title).toBeTruthy();
    expect(title.textContent).toContain('Dashboard');
    expect(title.classList).toContain('neon-glow');
  });

  it('should have metrics grid container', () => {
    fixture.detectChanges();

    const metricsGrid = fixture.nativeElement.querySelector('.metrics-grid');
    expect(metricsGrid).toBeTruthy();
  });

  it('should have charts grid container', () => {
    fixture.detectChanges();

    const chartsGrid = fixture.nativeElement.querySelector('.charts-grid');
    expect(chartsGrid).toBeTruthy();
  });
});
