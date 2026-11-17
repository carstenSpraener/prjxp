# Dashboard

The Dashboard is the primary monitoring view in the PRJXP Hub web UI. It provides real-time visibility into system health, query performance, and the status of indexed projects. All data is fetched via polling from the Hub backend and rendered without requiring a page reload.

The page is organized into three sections:

| Section | Content |
|---------|---------|
| **System Health** | Four metric cards (CPU, Memory, Disk, Connections) |
| **Query Stats** | Two chart panels (Response Times, Cache Performance) plus a summary |
| **Projects** | A table of indexed projects with chunk/embedding counts and live-update status |

A **Setup Status Banner** appears at the top when the Hub is not yet fully configured.

## System Health

The System Health section displays four `metric-card` components in a `.metrics-grid` container:

| Card | Data field | Example |
|------|-----------|---------|
| CPU | `cpuUsage` | 0.2 (20 %) |
| Memory | `usedMemory` / `totalMemory` | 8 / 16 GB |
| Disk | `freeDiskSpace` / `totalDiskSpace` | 10 / 20 GB |
| Connections | `activeConnections` | 2 |

The health data is polled every **5 seconds** via `DashboardService.pollHealth(5000)`. While the first response has not arrived, the section displays the text **"Loading system health"**. If the poll fails, the error is swallowed and an empty `SystemHealth` object is rendered, so the cards show zeroed values rather than an error state.

## Query Statistics

The Query Stats section contains two `chart-panel` components in a `.charts-grid` container:

### Response Times chart

| Data point | Source field |
|------------|-------------|
| Avg | `avgResponseTimeMs` |
| P95 | `p95ResponseTimeMs` |

### Cache Performance chart

| Data point | Source field |
|------------|-------------|
| Hits | `cacheHits` |
| Misses | `cacheMisses` |

Both charts are populated dynamically: each time the `pollQueryStats(10000)` observable emits, the component's `updateChartData` method maps the raw `QueryStats` fields into `ChartDataPoint` arrays. Missing values default to `0`.

The section also shows a **Total Queries** summary (`totalQueries`) and a list of `recentQueries`.

Query stats are polled every **10 seconds**. A failed poll is caught and replaced with an empty `QueryStats`, so the charts simply retain their last known values.

## Projects Table

The Projects section renders a table (one row per project) with the following columns:

| Column | Source field | Notes |
|--------|-------------|-------|
| Name | `name` | e.g. `demo` |
| Status | `status` | e.g. `READY` |
| Files | `fileCount` | Number of source files indexed |
| Chunks | `chunkCount` | Number of code chunks created |
| Embeddings | `embeddingCount` | Number of vectors stored |
| Live Update | `liveUpdate.status` | e.g. `IDLE` — rendered via `LiveUpdateIndicatorComponent` |

Projects are polled every **10 seconds** via `DashboardService.pollProjects(10000)`. While the first response is pending, the section shows **"Loading projects"**. If the poll fails, the table displays **"No projects found"**.

## Polling and Error Handling

All three data streams are set up in `ngOnInit` and use RxJS `catchError` to prevent a single failed HTTP call from breaking the UI:

```ts
// Health – 5 s interval
this.health$.set(
  this.dashboardService.pollHealth(5000).pipe(
    catchError(() => of({} as SystemHealth))
  )
);

// Query stats – 10 s interval, also feeds the charts
this.queryStats$.set(
  this.dashboardService.pollQueryStats(10000).pipe(
    catchError(() => of({} as QueryStats)),
    tap(stats => this.updateChartData(stats))
  )
);

// Projects – 10 s interval
this.projects$.set(
  this.dashboardService.pollProjects(10000).pipe(
    catchError(() => of([]))
  )
);
```

Because the observables are stored in Angular **signals** (`health$`, `queryStats$`, `projects$`) and consumed through `AsyncPipe`, the UI updates reactively on every poll without a full re-render.

## Setup Status Banner

A `SetupStatusBannerComponent` is rendered above the three sections. It is driven by `SetupService`, which exposes three signals:

| Signal | Purpose |
|--------|---------|
| `status` | Current setup state (e.g. `null` when ready) |
| `error` | Error message if setup failed |
| `loading` | `true` while the status check is in flight |

When the Hub is fully configured the banner is hidden; otherwise it surfaces the current setup state or error to the operator.

## Page Layout Summary

```
┌─────────────────────────────────────────────────────┐
│  h1 "Dashboard"  (class: neon-glow)                │
│  [SetupStatusBanner]                               │
├─────────────────────────────────────────────────────┤
│  .dashboard-section  –  System Health              │
│  ┌───────────────────────────────────────────────┐ │
│  │ .metrics-grid                                │ │
│  │  [CPU] [Memory] [Disk] [Connections]         │ │
│  └───────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│  .dashboard-section  –  Query Stats                │
│  ┌───────────────────────────────────────────────┐ │
│  │ .charts-grid                                 │ │
│  │  [Response Times chart] [Cache chart]        │ │
│  │  Total Queries: N   Recent queries list      │ │
│  └───────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│  .dashboard-section  –  Projects                   │
│  ┌───────────────────────────────────────────────┐ │
│  │ <table>  Name | Status | Files | Chunks | …  │ │
│  └───────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

The page title carries the `neon-glow` CSS class for the Hub's dark-theme visual style.

_This document was generated with Doc|Pipe._

