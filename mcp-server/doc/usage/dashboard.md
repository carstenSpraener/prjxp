## Dashboard

The Dashboard is the primary monitoring view in the PRJXP Hub web UI. It displays real-time system health metrics, query performance statistics, and a project overview. All data is fetched via polling—no page reload is required to see updated values.

The page is organized into three sections:

| Section | Content |
|---|---|
| **System Health** | Four metric cards (CPU, Memory, Disk, Connections) |
| **Query Stats** | Two chart panels (Response Times, Cache Performance) |
| **Projects** | Table of indexed projects with their status |

A **live-update indicator** in the header signals that data is being refreshed in the background.

---

## Layout

The page renders the following structural elements (top to bottom):

1. **Title** – An `<h1>` reading "Dashboard" with a `neon-glow` CSS class.
2. **Metrics grid** (`.metrics-grid`) – A responsive grid containing four `<metric-card>` components:
   - CPU
   - Memory
   - Disk
   - Connections
3. **Charts grid** (`.charts-grid`) – A responsive grid containing two `<chart-panel>` components:
   - **Response Times** – two data points: *Avg* and *P95*
   - **Cache Performance** – two data points: *Hits* and *Misses*
4. **Projects section** – A table listing all indexed projects.

Each of the three areas is wrapped in a `.dashboard-section` container.

---

## Data Sources and Polling Intervals

The `DashboardService` provides three observable streams. The component subscribes to them in `ngOnInit` with the following polling cadences:

| Stream | Service method | Poll interval | Fallback on error |
|---|---|---|---|
| System health | `pollHealth(5000)` | 5 s | `{}` (empty `SystemHealth`) |
| Query statistics | `pollQueryStats(10000)` | 10 s | `{}` (empty `QueryStats`) |
| Project list | `pollProjects(10000)` | 10 s | `[]` (empty array) |

All three streams are wrapped in `catchError` so that a missing or unreachable backend endpoint degrades gracefully to an empty value rather than crashing the UI.

A fourth service method, `getProjectStats()`, is available on the service but is **not** subscribed to by the dashboard component in its current implementation.

---

## Query Statistics Chart Data

When a new `QueryStats` payload arrives, the component's `updateChartData` method maps the raw fields into the two chart panels:

### Response Times chart

| Chart label | Source field | Fallback |
|---|---|---|
| Avg | `stats.avgResponseTimeMs` | `0` |
| P95 | `stats.p95ResponseTimeMs` | `0` |

### Cache Performance chart

| Chart label | Source field | Fallback |
|---|---|---|
| Hits | `stats.cacheHits` | `0` |
| Misses | `stats.cacheMisses` | `0` |

If a field is `null` or `undefined` in the API response, the chart renders `0` for that data point.

---

## Live Updates

The dashboard does **not** require a manual refresh. Because each stream is a polling observable:

- **System health** values (CPU, Memory, Disk, Connections) update every **5 seconds**.
- **Query stats** and the **project list** update every **10 seconds**.

The `LiveUpdateIndicatorComponent` in the header reflects the active polling state, giving the operator a visual cue that data is current.

---

## Graceful Degradation

If the Hub backend is not yet fully operational (for example, during initial startup or if a metrics endpoint has not been deployed), the dashboard still renders:

- Metric cards display `0` or a blank value.
- Chart panels show flat lines at `0`.
- The project table is empty.

No error dialogs or broken layouts appear; the `catchError` operators ensure the UI remains interactive while waiting for the backend to become available.

---

## Component Dependencies

The `DashboardComponent` (selector `app-dashboard`, standalone) imports the following child components and pipes:

| Dependency | Purpose |
|---|---|
| `MetricCardComponent` | Renders a single labelled numeric metric |
| `ChartPanelComponent` | Renders a small bar/line chart from `ChartDataPoint[]` |
| `LiveUpdateIndicatorComponent` | Visual indicator that polling is active |
| `AsyncPipe` | Bridges RxJS observables to the template |

The component holds its data in Angular **signals** (`health$`, `queryStats$`, `projects$`, `queryTimesData`, `cacheData`), which are updated reactively as new polling results arrive.

_This document was generated with Doc|Pipe._

