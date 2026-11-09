# Dashboard

The Dashboard is the primary monitoring view in the PRJXP Hub web UI. It provides a real-time overview of system health, query performance, and registered projects. All data is fetched from the Hub backend via the `DashboardService` and refreshed automatically on a polling interval—no page reload is required.

The page is rendered by the standalone Angular component `app-dashboard` and is composed of three logical sections: **System Health**, **Query Stats**, and **Projects**.

## System Health Metrics

The System Health section displays four metric cards in a responsive grid (`.metrics-grid`):

| Card | What it shows |
|------|---------------|
| CPU | Current CPU utilisation |
| Memory | Current memory usage |
| Disk | Current disk usage |
| Connections | Active connection count |

Each card is rendered by the `MetricCardComponent`. The underlying data is supplied by the `SystemHealth` model and polled every **5 seconds** (`pollHealth(5000)`).

If the backend health endpoint is unavailable, the component catches the error and renders an empty `SystemHealth` object, so the cards display zeroed or placeholder values rather than breaking the page.

## Query Statistics

The Query Stats section contains two chart panels (`.charts-grid`), each rendered by the `ChartPanelComponent`:

### Response Times

A two-point chart showing:

| Label | Source field |
|-------|-------------|
| Avg | `QueryStats.avgResponseTimeMs` |
| P95 | `QueryStats.p95ResponseTimeMs` |

### Cache Performance

A two-point chart showing:

| Label | Source field |
|-------|-------------|
| Hits | `QueryStats.cacheHits` |
| Misses | `QueryStats.cacheMisses` |

Both charts are updated reactively: every time the `pollQueryStats(10000)` observable emits a new `QueryStats` payload, the `updateChartData` method recomputes the chart data signals. Missing numeric fields default to `0`.

The query-stats poll runs every **10 seconds**.

## Project Overview

The Projects section lists all registered projects using the `ProjectInfo` model. The list is kept fresh by a 10-second poll (`pollProjects(10000)`), so newly added or removed projects appear without a manual refresh.

The section is always rendered (even when the project list is empty), ensuring the three-section layout remains stable.

## Live Updates & Polling

The Dashboard does not use WebSockets or server-sent events. Instead, it relies on **RxJS polling** with the following intervals:

| Observable | Interval | Purpose |
|------------|----------|---------|
| `health$` | 5 000 ms | Refresh CPU, Memory, Disk, Connections cards |
| `queryStats$` | 10 000 ms | Refresh response-time and cache charts |
| `projects$` | 10 000 ms | Refresh the project list and counts |

A `LiveUpdateIndicatorComponent` is displayed on the page to signal that data is being refreshed in the background.

All three observables are wrapped with `catchError(() => of(...))`, so a transient backend failure (for example, an endpoint that has not been deployed yet) results in an empty default object rather than an unhandled error. The UI continues to render with zeroed values until the next successful poll.

## Layout & Visual Design

The page follows a fixed three-section vertical layout:

```
┌─────────────────────────────────────────────┐
│  Dashboard  (h1, .neon-glow)               │
├─────────────────────────────────────────────┤
│  .metrics-grid                             │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐      │
│  │ CPU  │ │ Mem  │ │ Disk │ │ Conn │      │
│  └──────┘ └──────┘ └──────┘ └──────┘      │
├─────────────────────────────────────────────┤
│  .charts-grid                              │
│  ┌─────────────────┐ ┌─────────────────┐   │
│  │ Response Times  │ │ Cache Perf.     │   │
│  └─────────────────┘ └─────────────────┘   │
├─────────────────────────────────────────────┤
│  Projects table                            │
└─────────────────────────────────────────────┘
```

Key styling details:

- The page title (`<h1>Dashboard</h1>`) carries the `neon-glow` CSS class for a highlighted heading.
- Metric cards are laid out in a four-column grid (`.metrics-grid`).
- Chart panels are laid out in a two-column grid (`.charts-grid`).
- Each of the three sections is wrapped in a `.dashboard-section` container.

## Data Models

The Dashboard consumes three TypeScript models from the Hub API:

| Model | Key fields used by the Dashboard |
|-------|----------------------------------|
| `SystemHealth` | CPU, Memory, Disk, Connections values (bound to the four metric cards) |
| `QueryStats` | `avgResponseTimeMs`, `p95ResponseTimeMs`, `cacheHits`, `cacheMisses` |
| `ProjectInfo` | Project metadata for the project list (name, status, etc.) |

All three are delivered as RxJS `Observable`s via `DashboardService` and bound in the template with Angular's `AsyncPipe`.

_This document was generated with Doc|Pipe._

