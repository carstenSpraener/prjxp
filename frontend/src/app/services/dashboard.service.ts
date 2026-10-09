import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, interval, startWith, switchMap } from 'rxjs';
import { SystemHealth } from '../models/system-health.model';
import { QueryStats } from '../models/query-stats.model';
import { ProjectInfo } from '../models/project-info.model';
import { API_BASE } from '../config/api-config';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly baseUrl = API_BASE;

  constructor(private http: HttpClient) {}

  getSystemHealth(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>(`${this.baseUrl}/system/health`);
  }

  getQueryStats(): Observable<QueryStats> {
    return this.http.get<QueryStats>(`${this.baseUrl}/system/query-stats`);
  }

  getProjectStats(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>(`${this.baseUrl}/tools/projects`);
  }

  pollHealth(intervalMs: number = 5000): Observable<SystemHealth> {
    return interval(intervalMs).pipe(
      startWith(0),
      switchMap(() => this.getSystemHealth())
    );
  }

  pollQueryStats(intervalMs: number = 10000): Observable<QueryStats> {
    return interval(intervalMs).pipe(
      startWith(0),
      switchMap(() => this.getQueryStats())
    );
  }
}
