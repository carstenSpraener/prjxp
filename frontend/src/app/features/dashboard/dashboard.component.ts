import { Component, OnInit, signal } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { MetricCardComponent } from '../../components/metric-card/metric-card.component';
import { ChartPanelComponent, ChartDataPoint } from '../../components/chart-panel/chart-panel.component';
import { LiveUpdateIndicatorComponent } from '../../components/live-update-indicator/live-update-indicator.component';
import { DashboardService } from '../../services/dashboard.service';
import { SystemHealth } from '../../models/system-health.model';
import { QueryStats } from '../../models/query-stats.model';
import { ProjectInfo } from '../../models/project-info.model';
import { Observable, catchError, map, of, tap } from 'rxjs';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [AsyncPipe, MetricCardComponent, ChartPanelComponent, LiveUpdateIndicatorComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css'
})
export class DashboardComponent implements OnInit {
  health$ = signal<Observable<SystemHealth>>(of({} as SystemHealth));
  queryStats$ = signal<Observable<QueryStats>>(of({} as QueryStats));
  projects$ = signal<Observable<ProjectInfo[]>>(of([]));

  // Dynamic chart data derived from query stats
  queryTimesData = signal<ChartDataPoint[]>([]);
  cacheData = signal<ChartDataPoint[]>([]);

  constructor(private dashboardService: DashboardService) {}

  ngOnInit(): void {
    // Start polling (with error handling for when backend endpoints don't exist yet)
    this.health$.set(
      this.dashboardService.pollHealth(5000).pipe(catchError(() => of({} as SystemHealth)))
    );

    // Query stats with automatic chart data updates
    this.queryStats$.set(
      this.dashboardService.pollQueryStats(10000).pipe(
        catchError(() => of({} as QueryStats)),
        tap(stats => this.updateChartData(stats))
      )
    );

    // Poll so the live-update indicator (and counts) stay fresh without a reload
    this.projects$.set(
      this.dashboardService.pollProjects(10000).pipe(catchError(() => of([])))
    );
  }

  private updateChartData(stats: QueryStats): void {
    // Query response times from real API data
    this.queryTimesData.set([
      { label: 'Avg', value: stats.avgResponseTimeMs ?? 0 },
      { label: 'P95', value: stats.p95ResponseTimeMs ?? 0 }
    ]);

    // Cache hit/miss from real API data
    this.cacheData.set([
      { label: 'Hits', value: stats.cacheHits ?? 0 },
      { label: 'Misses', value: stats.cacheMisses ?? 0 }
    ]);
  }
}
