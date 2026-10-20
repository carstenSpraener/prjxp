# Angular Frontend für prjxp

## Ziel
Separates Angular 19+ Frontend mit:
- **Dark-Mode Cyberpunk** Design (Neon-Akzenten, futuristisch)
- **Import-Workflow**: TAR/ZIP-Upload + Live-Verzeichnis-Monitoring
- **Grafana-Dashboard**: Index-Stats, Query-Perf, System-Health
- **Projekt-Management**: CRUD mit YAML-Editor für `prjxp.yaml`
- **Hosting**: Separater Port (4200 dev / 8080 prod), API-Proxy zu mcp-server:7007

## Architektur

```
┌─────────────────────────────────────────────┐
│  Angular Frontend (Port 4200/8080)          │
│  ┌──────────┐ ┌──────────┐ ┌─────────────┐ │
│  │ Dashboard │ │ Import   │ │ Project Mgmt │ │
│  └──────────┘ └──────────┘ └─────────────┘ │
│         ↕ Proxy (dev) / Direct Call (prod)   │
└─────────────────────┬─────────────────────────┘
                      │
              ┌───────▼───────┐
              │  mcp-server    │
              │  (Port 7007)   │
              └─────────────────┘
```

## Phasen-Übersicht

| Phase | Titel | Fokus |
|-------|-------|-------|
| 01 | Project Scaffolding & Base Setup | Angular 19+, Proxy, Dependencies |
| 02 | Core Layout & Cyberpunk Theme | Shell, Navigation, Dark-Mode CSS |
| 03 | Import Module | TAR/ZIP Upload + Live Monitoring |
| 04 | Grafana Dashboard | Metriken, Charts, Real-Time Updates |
| 05 | Project Explorer & Management | CRUD + YAML-Editor Dialog |
| 06 | API Backend Extensions | Neue Endpoints für Stats & CRUD |

## API-Endpunkte (mcp-server:7007)

### Bestehende Endpoints
| Method | Path | Purpose | Response |
|--------|------|---------|----------|
| GET | `/prjxp/tools/ping` | Health check | `"pong!"` |
| GET | `/prjxp/tools/projects` | List projects | `List<ProjectInfo>` |
| GET | `/prjxp/tools/context` | RAG search | Context enrichment result |
| GET | `/prjxp/tools/vectorSearch` | Semantic search | `List<ScoredChunk>` |
| GET | `/prjxp/tools/grep` | Full-text search | `List<SearchHit>` |
| GET | `/prjxp/tools/byIndex` | Symbol lookup | `FileView` |
| GET | `/prjxp/tools/meta-search-params` | Search param schema | `MetaSearchParamsResponse` |
| DELETE | `/prjxp/projects/{name}` | Delete project | 204 No Content |
| POST | `/prjxp/projects/{name}/reindex` | Re-index project | 204 No Content |
| POST | `/prjxp/projects/{name}/importEmbeddings` | Import embeddings (multipart) | 204 No Content |

### Neue Endpoints (Phase 06)
| Method | Path | Purpose | Response |
|--------|------|---------|----------|
| POST | `/prjxp/projects` | Create project (LIVE or SNAPSHOT) | `ProjectInfo` |
| PUT | `/prjxp/projects/{name}` | Update project config (YAML) | `ProjectInfo` |
| GET | `/prjxp/projects/{name}/stats` | Project statistics (chunks, embeddings) | `ProjectStats` |
| GET | `/prjxp/system/health` | System health metrics (CPU, RAM, Disk) | `SystemHealth` |
| GET | `/prjxp/system/query-stats` | Query performance statistics | `QueryStats` |
| POST | `/prjxp/projects/upload` | Upload TAR/ZIP archive (multipart) | `ProjectInfo` |
| GET | `/prjxp/live-scan` | Scan directories for live projects | `List<LiveProjectCandidate>` |

## Datenmodelle (Inline)

### ProjectInfo (erweitert)
```java
record ProjectInfo(
    String name,
    String status,        // IMPORTING|CHUNKING|EMBEDDING|READY|FAILED
    String kind,          // LIVE|SNAPSHOT (new)
    String lastError,
    long chunkCount,      // new
    long embeddingCount,   // new
    int fileCount          // new
)
```

### ProjectStats (neu)
```java
record ProjectStats(
    String name,
    long chunkCount,
    long embeddingCount,
    int fileCount,
    String rootDir,
    List<String> languages  // chunoWhiteList split
)
```

### SystemHealth (neu)
```java
record SystemHealth(
    double cpuUsage,       // 0.0-1.0
    long totalMemory,      // bytes
    long usedMemory,       // bytes
    long freeDiskSpace,    // bytes
    long totalDiskSpace,   // bytes
    int activeConnections  // current HTTP connections
)
```

### QueryStats (neu)
```java
record QueryStats(
    long totalQueries,
    double avgResponseTimeMs,
    double p95ResponseTimeMs,
    long cacheHits,
    long cacheMisses,
    List<QueryStatEntry> recentQueries  // last 10 queries
)

record QueryStatEntry(
    String query,
    long responseTimeMs,
    int resultCount,
    boolean fromCache
)
```

### LiveProjectCandidate (neu)
```java
record LiveProjectCandidate(
    String name,
    Path directory,
    boolean hasConfigFile,   // prjxp.yaml/.yml exists?
    List<String> languages   // detected file extensions
)
```

### ProjectCreateRequest (neu - für POST /prjxp/projects)
```java
record ProjectCreateRequest(
    String name,
    String kind,            // "LIVE" or "SNAPSHOT"
    String rootDir,         // for LIVE: directory path; for SNAPSHOT: tar/zip path
    String chunoWhiteList,  // default "java,ts"
    int tibedBatchSize      // default 32
)
```

### ProjectUpdateRequest (neu - für PUT /prjxp/projects/{name})
```java
record ProjectUpdateRequest(
    String rootDir,         // optional update
    String chunoWhiteList,  // optional update
    int tibedBatchSize      // optional update
)
```

## Design-System (Cyberpunk Dark Mode)

### Farbpalette
| Token | Value | Usage |
|-------|-------|-------|
| `--bg-primary` | `#0a0e17` | Main background |
| `--bg-secondary` | `#141b2d` | Cards, panels |
| `--bg-tertiary` | `#1e2740` | Hover states, inputs |
| `--accent-cyan` | `#00f5ff` | Primary accent, links |
| `--accent-magenta` | `#ff00ff` | Secondary accent, highlights |
| `--accent-green` | `#39ff14` | Success states |
| `--accent-orange` | `#ff8c00` | Warning states |
| `--text-primary` | `#e0e6f0` | Main text |
| `--text-secondary` | `#8892a4` | Muted text, labels |
| `--border-color` | `#2a3450` | Borders, dividers |

### Typography
- **Primary Font**: `Inter` (Google Fonts) - clean, modern
- **Mono Font**: `JetBrains Mono` (Google Fonts) - code blocks, terminal output
- **Headings**: Bold, uppercase with letter-spacing

### Effekte
- **Neon Glow**: `box-shadow: 0 0 10px var(--accent-cyan), 0 0 20px rgba(0,245,255,0.3)`
- **Glass Panels**: `backdrop-filter: blur(10px); background: rgba(20,27,45,0.8)`
- **Animated Borders**: Gradient border animation on focus/hover

## Projektstruktur (Angular)

```
frontend/
├── angular.json              # Angular config
├── package.json             # Dependencies
├── tsconfig.json           # TypeScript config
├── proxy.conf.dev.json     # Dev proxy to mcp-server:7007
├── src/
│   ├── index.html          # Entry point
│   ├── main.ts             # Bootstrap
│   ├── styles.css          # Global styles (Cyberpunk theme)
│   ├── app/
│   │   ├── app.config.ts   # App config (providers)
│   │   ├── app.routes.ts   # Route definitions
│   │   ├── app.component.ts # Root component (shell layout)
│   │   ├── services/        # API services
│   │   ├── models/          # TypeScript interfaces
│   │   ├── components/      # Shared components
│   │   ├── features/        # Feature modules (dashboard, import, projects)
│   │       ├── dashboard/    # Grafana-style dashboard
│   │       ├── import/      # Import workflow (upload + live monitoring)
│   │       └── projects/    # Project management & YAML editor
│   └── assets/             # Static assets (icons, images)
```

## Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `@angular/core` | 19.x | Angular framework |
| `@angular/common/http` | - | HTTP client |
| `@angular/router` | - | Routing |
| `@angular/forms` | - | Reactive forms (YAML editor) |
| `ng-zorro-antd` | 19.x | UI component library (optional) |
| `chart.js` + `ng2-charts` | latest | Dashboard charts |
| `rxjs` | 7.x | Reactive programming |

## Build & Serve Commands

```bash
# Development (with proxy to mcp-server:7007)
ng serve --proxy-config proxy.conf.dev.json

# Production build
ng build --configuration production

# Tests
ng test  # Karma + Jasmine
```

## Proxy Configuration (proxy.conf.dev.json)

```json
{
  "/prjxp": {
    "target": "http://localhost:7007",
    "secure": false,
    "changeOrigin": true
  }
}
```

## Test-Strategie
- **Unit Tests**: Jasmine + Karma, >= 80% LOC coverage
- **Component Tests**: Shallow rendering mit TestBed
- **Service Tests**: HttpClientTestingModule für API-Mocks
- **E2E Tests**: Playwright (optional, später)
