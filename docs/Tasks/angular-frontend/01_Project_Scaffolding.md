# Phase 01: Project Scaffolding & Base Setup

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope
* **Objective:** Create the Angular 19+ project structure with all base configuration files, proxy setup for mcp-server communication, and the shell layout component.
* **Target Files to Create/Modify:**
    * Create: `frontend/angular.json`
    * Create: `frontend/package.json`
    * Create: `frontend/tsconfig.json`
    * Create: `frontend/tsconfig.app.json`
    * Create: `frontend/tsconfig.spec.json`
    * Create: `frontend/proxy.conf.dev.json`
    * Create: `frontend/src/index.html`
    * Create: `frontend/src/main.ts`
    * Create: `frontend/src/styles.css`
    * Create: `frontend/src/app/app.config.ts`
    * Create: `frontend/src/app/app.routes.ts`
    * Create: `frontend/src/app/app.component.ts`
    * Create: `frontend/src/app/app.component.html`
    * Create: `frontend/src/app/app.component.css`
    * Create: `frontend/src/app/services/prjxp-api.service.ts`
    * Create: `frontend/src/app/models/project-info.model.ts`
    * Create: `frontend/src/app/services/prjxp-api.service.spec.ts`
    * Create: `frontend/src/app/app.component.spec.ts`

## 2. Inline Required Context (Contracts & Signatures)

### Angular 19+ Standalone Pattern
All components are standalone (no NgModules). Use `provideHttpClient()` for HTTP, `provideRouter()` for routing.

### Proxy Configuration (`proxy.conf.dev.json`)
```json
{
  "/prjxp": {
    "target": "http://localhost:7007",
    "secure": false,
    "changeOrigin": true
  }
}
```

### Package Dependencies (`package.json`)
```json
{
  "name": "prjxp-frontend",
  "version": "0.0.1",
  "scripts": {
    "ng": "ng",
    "start": "ng serve --proxy-config proxy.conf.dev.json",
    "build": "ng build --configuration production",
    "watch": "ng build --watch --configuration development",
    "test": "ng test"
  },
  "dependencies": {
    "@angular/animations": "^19.0.0",
    "@angular/common": "^19.0.0",
    "@angular/compiler": "^19.0.0",
    "@angular/core": "^19.0.0",
    "@angular/forms": "^19.0.0",
    "@angular/platform-browser": "^19.0.0",
    "@angular/platform-browser-dynamic": "^19.0.0",
    "@angular/router": "^19.0.0",
    "chart.js": "^4.4.0",
    "ng2-charts": "^8.0.0",
    "rxjs": "~7.8.0"
  },
  "devDependencies": {
    "@angular-devkit/build-angular": "^19.0.0",
    "@angular/cli": "^19.0.0",
    "@angular/compiler-cli": "^19.0.0",
    "@types/jasmine": "~5.1.0",
    "jasmine-core": "~5.4.0",
    "karma": "~6.9.0",
    "karma-chrome-launcher": "~3.2.0",
    "karma-coverage": "~2.2.0",
    "karma-jasmine": "~5.1.0",
    "karma-jasmine-html-reporter": "~2.1.0",
    "typescript": "~5.6.0"
  }
}
```

### TypeScript Config (`tsconfig.json`)
```json
{
  "compileOnSave": false,
  "compilerOptions": {
    "outDir": "./dist/out-tsc",
    "forceConsistentCasingInFileNames": true,
    "strict": true,
    "noImplicitOverride": true,
    "noPropertyAccessFromIndexSignature": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "skipLibCheck": true,
    "esModuleInterop": true,
    "sourceMap": true,
    "declaration": false,
    "experimentalDecorators": true,
    "moduleResolution": "bundler",
    "importHelpers": true,
    "target": "ES2022",
    "module": "ES2022",
    "useDefineForClassFields": false,
    "lib": ["ES2022", "dom"]
  },
  "angularCompilerOptions": {
    "enableI18nLegacyMessageIdFormat": false,
    "strictInjectionParameters": true,
    "strictInputAccessModifiers": true,
    "strictTemplates": true
  }
}
```

## 3. Implementation Details

### 3.1 Shell Layout (`app.component.*`)
The shell has:
- **Top bar**: prjxp logo + title "PRJXP HUB"
- **Left sidebar navigation** (collapsible): Dashboard, Import, Projects
- **Main content area**: `<router-outlet>`

Navigation items:
```typescript
interface NavItem {
  label: string;
  route: string;
  icon: string;  // use emoji or simple SVG for now
}

const NAV_ITEMS: NavItem[] = [
  { label: 'Dashboard', route: '/dashboard', icon: '📊' },
  { label: 'Import', route: '/import', icon: '📥' },
  { label: 'Projects', route: '/projects', icon: '📁' }
];
```

### 3.2 API Service (`prjxp-api.service.ts`)
Base service for all mcp-server communication:

```typescript
@Injectable({ providedIn: 'root' })
export class PrjxpApiService {
  private readonly baseUrl = '/prjxp';

  constructor(private http: HttpClient) {}

  // Health check
  ping(): Observable<string> {
    return this.http.get(`${this.baseUrl}/tools/ping`, { responseType: 'text' });
  }

  // Project endpoints
  getProjects(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>(`${this.baseUrl}/tools/projects`);
  }

  deleteProject(name: string): Observable<HttpEvent<void>> {
    return this.http.delete(`${this.baseUrl}/projects/${name}`, { observe: 'events' });
  }

  reindexProject(name: string): Observable<HttpEvent<void>> {
    return this.http.post(`${this.baseUrl}/projects/${name}/reindex`, null, { observe: 'events' });
  }

  importEmbeddings(name: string, file: File): Observable<HttpEvent<void>> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post(`${this.baseUrl}/projects/${name}/importEmbeddings`, formData, { observe: 'events' });
  }

  // Search endpoints (for future use)
  vectorSearch(query: string, project?: string, limit: number = 20): Observable<any[]> {
    const params: any = { query, limit };
    if (project) params.project = project;
    return this.http.get(`${this.baseUrl}/tools/vectorSearch`, { params });
  }

  grepSearch(pattern: string): Observable<any[]> {
    return this.http.get(`${this.baseUrl}/tools/grep`, { params: { pattern } });
  }
}
```

### 3.3 ProjectInfo Model (`project-info.model.ts`)
TypeScript interface matching the Java record:

```typescript
export interface ProjectInfo {
  name: string;
  status: 'IMPORTING' | 'CHUNKING' | 'EMBEDDING' | 'READY' | 'FAILED';
  kind?: 'LIVE' | 'SNAPSHOT';  // optional, added in Phase 06
  lastError?: string;
  chunkCount?: number;           // optional, added in Phase 06
  embeddingCount?: number;       // optional, added in Phase 06
  fileCount?: number;            // optional, added in Phase 06
}
```

### 3.4 Cyberpunk Theme (`styles.css`)
Global CSS variables and base styles:

```css
:root {
  --bg-primary: #0a0e17;
  --bg-secondary: #141b2d;
  --bg-tertiary: #1e2740;
  --accent-cyan: #00f5ff;
  --accent-magenta: #ff00ff;
  --accent-green: #39ff14;
  --accent-orange: #ff8c00;
  --text-primary: #e0e6f0;
  --text-secondary: #8892a4;
  --border-color: #2a3450;
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  background: var(--bg-primary);
  color: var(--text-primary);
  line-height: 1.6;
}

/* Neon glow utility */
.neon-glow {
  box-shadow: 0 0 10px var(--accent-cyan), 0 0 20px rgba(0, 245, 255, 0.3);
}

/* Glass panel utility */
.glass-panel {
  backdrop-filter: blur(10px);
  background: rgba(20, 27, 45, 0.8);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

/* Code/mono font */
code, pre, .mono {
  font-family: 'JetBrains Mono', monospace;
}
```

### 3.5 Routes (`app.routes.ts`)
Placeholder routes (features will be implemented in later phases):

```typescript
import { Routes } from '@angular/router';
import { Component } from '@angular/core';

// Placeholder components for routes not yet implemented
@Component({
  selector: 'app-placeholder',
  standalone: true,
  template: `<div class="glass-panel" style="padding:2rem;text-align:center">
    <h2>Coming Soon</h2>
    <p>This feature is under construction.</p>
  </div>`
})
export class PlaceholderComponent {}

export const routes: Routes = [
  { path: 'dashboard', component: PlaceholderComponent },
  { path: 'import', component: PlaceholderComponent },
  { path: 'projects', component: PlaceholderComponent },
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: '**', redirectTo: '/dashboard' }
];
```

### 3.6 App Config (`app.config.ts`)
```typescript
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient()
  ]
};
```

### 3.7 Main Entry (`main.ts`)
```typescript
import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
```

### 3.8 Index HTML (`index.html`)
```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>PRJXP Hub</title>
  <base href="/">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="icon" type="image/svg+xml" href="assets/favicon.svg">
  <!-- Google Fonts -->
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
</head>
<body>
  <app-root></app-root>
</body>
</html>
```

## 4. Tests (TDD)

### 4.1 PrjxpApiService Tests
```typescript
// prjxp-api.service.spec.ts
describe('PrjxpApiService', () => {
  let service: PrjxpApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientModule]
    });
    service = TestBed.inject(PrjxpApiService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('ping() should call /prjxp/tools/ping', () => {
    // Use HttpClientTestingModule in actual test
  });

  it('getProjects() should call /prjxp/tools/projects', () => {
    // Test HTTP call
  });

  it('deleteProject() should call DELETE /prjxp/projects/{name}', () => {
    // Test HTTP call
  });

  it('reindexProject() should call POST /prjxp/projects/{name}/reindex', () => {
    // Test HTTP call
  });

  it('importEmbeddings() should send FormData to /prjxp/projects/{name}/importEmbeddings', () => {
    // Test multipart upload
  });

  it('vectorSearch() should call GET /prjxp/tools/vectorSearch with params', () => {
    // Test query params
  });

  it('grepSearch() should call GET /prjxp/tools/grep with params', () => {
    // Test query params
  });
});
```

### 4.2 AppComponent Tests
```typescript
// app.component.spec.ts
describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent]
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it(`should have title 'PRJXP Hub'`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('h1')?.textContent).toContain('PRJXP');
  });

  it('should render 3 navigation items', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const navItems = fixture.nativeElement.querySelectorAll('nav a');
    expect(navItems.length).toBe(3);
  });

  it('should have router outlet', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('router-outlet')).toBeTruthy();
  });
});
```

## 5. Acceptance Criteria
- [ ] `cd frontend && npm install` succeeds
- [ ] `ng serve --proxy-config proxy.conf.dev.json` starts on port 4200
- [ ] Shell layout renders with top bar, sidebar nav (3 items), and router outlet
- [ ] Cyberpunk dark theme is applied (CSS variables, fonts loaded)
- [ ] All tests pass with >= 80% coverage
- [ ] Proxy correctly forwards `/prjxp/*` to `localhost:7007`
