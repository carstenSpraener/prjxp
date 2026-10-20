# Phase 04: Grafana-Style Dashboard

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans.
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document.
> 4. **MANDATORY FINAL STEP:** Execute `/compact` IMMEDIATELY after green tests (>= 80% coverage).

## 1. Target & Scope
* **Objective:** Build a Grafana-style dashboard with real-time metrics: index statistics, query performance, and system health.
* **Target Files to Create/Modify:**
    * Modify: `frontend/src/app/app.routes.ts` - Replace placeholder for `/dashboard` route
    * Create: `frontend/src/app/features/dashboard/dashboard.component.ts`
    * Create: `frontend/src/app/features/dashboard/dashboard.component.html`
    * Create: `frontend/src/app/features/dashboard/dashboard.component.css`
    * Create: `frontend/src/app/features/dashboard/dashboard.component.spec.ts`
    * Create: `frontend/src/app/services/dashboard.service.ts`
    * Create: `frontend/src/app/services/dashboard.service.spec.ts`
    * Create: `frontend/src/app/models/system-health.model.ts`
    * Create: `frontend/src/app/models/query-stats.model.ts`
    * Create: `frontend/src/app/components/metric-card/metric-card.component.ts`
    * Create: `frontend/src/app/components/metric-card/metric-card.component.html`
    * Create: `frontend/src/app/components/metric-card/metric-card.component.css`
    * Create: `frontend/src/app/components/metric-card/metric-card.component.spec.ts`
    * Create: `frontend/src/app/components/chart-panel/chart-panel.component.ts`
    * Create: `frontend/src/app/components/chart-panel/chart-panel.component.html`
    * Create: `frontend/src/app/components/chart-panel/chart-panel.component.css`

## 2. Inline Required Context

### REST Endpoints (from mcp-server:7007)
| Method | Path | Purpose | Response |
|--------|------|---------|----------|
| GET | `/prjxp/system/health` | System health metrics (Phase 06) | `SystemHealth` |
| GET | `/prjxp/system/query-stats` | Query performance stats (Phase 06) | `QueryStats` |
| GET | `/prjxp/tools/projects` | List projects with stats (Phase 06 extended) | `List<ProjectInfo>` |

### SystemHealth Model
```typescript
export interface SystemHealth {
  cpuUsage: number;        // 0.0-1.0
  totalMemory: number;     // bytes
  usedMemory: number;      // bytes
  freeDiskSpace: number;   // bytes
  totalDiskSpace: number;  // bytes
  activeConnections: number;
}
```

### QueryStats Model
```typescript
export interface QueryStats {
  totalQueries: number;
  avgResponseTimeMs: number;
  p95ResponseTimeMs: number;
  cacheHits: number;
  cacheMisses: number;
  recentQueries: QueryStatEntry[];  // last 10 queries
}

export interface QueryStatEntry {
  query: string;
  responseTimeMs: number;
  resultCount: number;
  fromCache: boolean;
}
```

### DashboardService Interface
```typescript
@Injectable({ providedIn: 'root' })
export class DashboardService {
  constructor(private http: HttpClient) {}

  getSystemHealth(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>('/prjxp/system/health');
  }

  getQueryStats(): Observable<QueryStats> {
    return this.http.get<QueryStats>('/prjxp/system/query-stats');
  }

  getProjectStats(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>('/prjxp/tools/projects');
  }

  // Polling helper: returns interval of health checks
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
```

## 3. Implementation Details

### 3.1 Dashboard Layout (HTML)
Three-section layout:
- **Top Row**: Metric cards (CPU, Memory, Disk, Active Connections)
- **Middle Row**: Charts (Query response times, Cache hit/miss ratio)
- **Bottom Row**: Project overview table with chunk/embedding counts

```html
<div class="dashboard">
  <h1 class="dashboard-title neon-glow">System Dashboard</h1>

  <!-- Metric Cards Row -->
  <div class="metric-row">
    <metric-card label="CPU Usage" [value]="(health$ | async)?.cpuUsage"
                  unit="%" [color]="'cyan'"></metric-card>
    <metric-card label="Memory" [value]="(health$ | async)?.usedMemory"
                  [maxValue]="(health$ | async)?.totalMemory" unit="GB" [color]="'magenta'"></metric-card>
    <metric-card label="Disk Space" [value]="(health$ | async)?.freeDiskSpace"
                  [maxValue]="(health$ | async)?.totalDiskSpace" unit="GB" [color]="'green'"></metric-card>
    <metric-card label="Connections" [value]="(health$ | async)?.activeConnections"
                  unit="" [color]="'orange'"></metric-card>
  </div>

  <!-- Charts Row -->
  <div class="charts-row">
    <chart-panel title="Query Response Times" [data]="queryTimesData"></chart-panel>
    <chart-panel title="Cache Hit/Miss Ratio" [data]="cacheData"></chart-panel>
  </div>

  <!-- Project Overview -->
  <div class="project-overview glass-panel">
    <h2>Project Statistics</h2>
    <table>
      <thead><tr><th>Project</th><th>Status</th><th>Chunks</th><th>Embeddings</th></tr></thead>
      <tbody>
        <tr *ngFor="let project of (projects$ | async)">
          <td>{{project.name}}</td>
          <td [class]="project.status.toLowerCase()">{{project.status}}</td>
          <td>{{project.chunkCount ?? 0}}</td>
          <td>{{project.embeddingCount ?? 0}}</td>
        </tr>
      </tbody>
    </table>
  </div>
</div>
```

### 3.2 MetricCard Component
Compact card with:
- Label (muted text)
- Value (large, neon-colored based on `color` input)
- Optional progress bar (if `maxValue` is provided)
- Unit suffix

### 3.3 ChartPanel Component
Uses `ng2-charts` (Chart.js wrapper):
- Line chart for response times over time
- Doughnut chart for cache hit/miss ratio
- Dark-themed chart colors matching cyberpunk palette

### 3.4 Dashboard CSS
```css
.dashboard {
  padding: 2rem;
  max-width: 1400px;
  margin: 0 auto;
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
  margin-bottom: 2rem;
}

.charts-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1rem;
  margin-bottom: 2rem;
}

/* Responsive */
@media (max-width: 768px) {
  .metric-row, .charts-row {
    grid-template-columns: 1fr;
  }
}
```

## 4. Tests (TDD)

### DashboardService Tests
- `getSystemHealth()` should GET `/prjxp/system/health`
- `getQueryStats()` should GET `/prjxp/system/query-stats`
- `pollHealth()` should emit at configured interval using switchMap
- `pollQueryStats()` should emit at configured interval

### DashboardComponent Tests
- Should display metric cards with correct labels and values
- `health$` should poll system health at 5s interval
- `projects$` should load project list on init
- Table should show chunk/embedding counts from ProjectInfo

### MetricCard Tests
- Should render label, value, and unit
- Progress bar should show correct percentage when maxValue is provided
- Color input should apply correct neon color class

### ChartPanel Tests
- Should render chart with provided data
- Title should be displayed above chart

## 5. Acceptance Criteria
- [ ] Dashboard shows real-time system health metrics (CPU, Memory, Disk)
- [ ] Charts display query response times and cache statistics
- [ ] Project table shows chunk/embedding counts per project
- [ ] Auto-refresh every 5s for health, 10s for query stats
- [ ] Responsive layout on mobile (single column)
- [ ] All tests pass with >= 80% coverage
