import { TestBed } from '@angular/core/testing';
import { DashboardComponent } from './dashboard.component';
import { DashboardService } from '../../services/dashboard.service';
import { MetricCardComponent } from '../../components/metric-card/metric-card.component';
import { ChartPanelComponent } from '../../components/chart-panel/chart-panel.component';
import { SystemHealth } from '../../models/system-health.model';
import { QueryStats } from '../../models/query-stats.model';
import { ProjectInfo } from '../../models/project-info.model';
import { of } from 'rxjs';
import { vi } from 'vitest';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: any;

  beforeEach(async () => {
    // Mock DashboardService to prevent HTTP calls during component creation
    const mockDashboardService = {
      pollHealth: vi.fn().mockReturnValue(of({} as SystemHealth)),
      pollQueryStats: vi.fn().mockReturnValue(of({} as QueryStats)),
      getProjectStats: vi.fn().mockReturnValue(of([]))
    };

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: DashboardService, useValue: mockDashboardService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('ngOnInit should set up observables', () => {
    component.ngOnInit();

    // Verify the service methods were called
    const dashboardService = TestBed.inject(DashboardService);
    expect(dashboardService.pollHealth).toHaveBeenCalled();
    expect(dashboardService.pollQueryStats).toHaveBeenCalled();
    expect(dashboardService.getProjectStats).toHaveBeenCalled();
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

    expect(component.queryTimesData().length).toBe(2);
    expect(component.queryTimesData()[0].label).toBe('Avg');
    expect(component.queryTimesData()[1].label).toBe('P95');
  });

  it('cacheData should have sample data after ngOnInit', () => {
    component.ngOnInit();

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
