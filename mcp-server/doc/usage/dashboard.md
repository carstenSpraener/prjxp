# Dashboard

The Dashboard is the primary monitoring view in the PRJXP Hub web UI. It provides a real-time overview of system health, query performance, and indexed projects. Data is refreshed automatically via polling—no manual reload is required.

The page is organized into three sections:

| Section | Content |
|---|---|
| **System Health** | Four metric cards (CPU, Memory, Disk, Connections) |
| **Query Stats** | Two chart panels (Response Times, Cache Performance) |
| **Projects** | Table of indexed projects with live counts |

A **live-update indicator** in the header signals that data is being refreshed in the background.

## Layout and Visual Structure

The dashboard renders the following elements in order:

- **Title** – An `<h1>` reading "Dashboard" with a `neon-glow` CSS class for the PRJXP visual theme.
- **Metrics grid** (`.metrics-grid`) – A responsive grid containing four `<metric-card>` components:
  - CPU
  - Memory
  - Disk
  - Connections
- **Charts grid** (`.charts-grid`) – A responsive grid containing two `<chart-panel>` components:
  - **Response Times** – displays *Avg* and *P95* latency values.
  - **Cache Performance** – displays *Hits* and *Misses* counts.
- **Projects section** – A table listing all indexed projects. The section is always rendered, even when the project list is empty.

Each of the three areas is wrapped in a `.dashboard-section` container.

## Data Sources and Polling

The `DashboardComponent` subscribes to three observables provided by `DashboardService`. Each observable is polled on a fixed interval and wrapped with `catchError` so the UI degrades gracefully if a backend endpoint is unavailable.

| Observable | Service method | Poll interval | Fallback on error |
|---|---|---|---|
| `health$` | `pollHealth(5000)` | 5 s | Empty `SystemHealth` object |
| `queryStats$` | `pollQueryStats(10000)` | 10 s | Empty `QueryStats` object |
| `projects$` | `pollProjects(10000)` | 10 s | Empty array `[]` |

All three subscriptions are established in `ngOnInit()`. The `queryStats$` stream additionally pipes through a `tap()` operator that calls `updateChartData()` to keep the chart signals in sync with the latest API response.

## Chart Data

Two reactive signals drive the chart panels. They are populated from the `QueryStats` model each time the 10-second poll returns:

### Response Times chart

| Data point label | Source field |
|---|---|
| `Avg` | `stats.avgResponseTimeMs` (defaults to `0`) |
| `P95` | `stats.p95ResponseTimeMs` (defaults to `0`) |

### Cache Performance chart

| Data point label | Source field |
|---|---|
| `Hits` | `stats.cacheHits` (defaults to `0`) |
| `Misses` | `stats.cacheMisses` (defaults to `0`) |

Each chart panel receives an array of `ChartDataPoint` objects (`{ label, value }`).

## Models

The dashboard consumes three data models:

- **`SystemHealth`** – CPU, memory, disk, and connection metrics displayed in the metric cards.
- **`QueryStats`** – Response-time percentiles and cache hit/miss counters displayed in the charts.
- **`ProjectInfo`** – Per-project metadata (name, index status, etc.) displayed in the projects table.

## Live-Update Indicator

A `<live-update-indicator>` component is rendered in the dashboard header. It is driven by the same polling streams, so it reflects whether the most recent poll cycle has completed. Because the projects stream polls every 10 seconds, the indicator (and any project counts) stay current without requiring the user to reload the page.

## Error Handling

Each polling stream is wrapped with `catchError`:

```ts
this.health$.set(
  this.dashboardService.pollHealth(5000).pipe(
    catchError(() => of({} as SystemHealth))
  )
);
```

If a backend endpoint is not yet available (for example, during initial deployment or a transient outage), the corresponding UI section renders with zeroed or empty values rather than throwing an error. The next successful poll restores real data automatically.

_This document was generated with Doc|Pipe._

