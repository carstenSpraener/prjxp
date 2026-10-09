# Phase 05: Project Explorer & YAML Editor

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans.
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document.
> 4. **MANDATORY FINAL STEP:** Execute `/compact` IMMEDIATELY after green tests (>= 80% coverage).

## 1. Target & Scope
* **Objective:** Build the project management view with CRUD operations and a YAML-editor dialog for creating/editing `prjxp.yaml` configurations.
* **Target Files to Create/Modify:**
    * Modify: `frontend/src/app/app.routes.ts` - Replace placeholder for `/projects` route
    * Create: `frontend/src/app/features/projects/projects.component.ts`
    * Create: `frontend/src/app/features/projects/projects.component.html`
    * Create: `frontend/src/app/features/projects/projects.component.css`
    * Create: `frontend/src/app/features/projects/projects.component.spec.ts`
    * Create: `frontend/src/app/services/project-management.service.ts`
    * Create: `frontend/src/app/services/project-management.service.spec.ts`
    * Create: `frontend/src/app/models/prjxp-yaml-config.model.ts`
    * Create: `frontend/src/app/components/yaml-editor-dialog/yaml-editor-dialog.component.ts`
    * Create: `frontend/src/app/components/yaml-editor-dialog/yaml-editor-dialog.component.html`
    * Create: `frontend/src/app/components/yaml-editor-dialog/yaml-editor-dialog.component.css`
    * Create: `frontend/src/app/components/yaml-editor-dialog/yaml-editor-dialog.component.spec.ts`
    * Create: `frontend/src/app/components/project-detail-card/project-detail-card.component.ts`
    * Create: `frontend/src/app/components/project-detail-card/project-detail-card.component.html`
    * Create: `frontend/src/app/components/project-detail-card/project-detail-card.component.css`

## 2. Inline Required Context

### REST Endpoints (from mcp-server:7007)
| Method | Path | Purpose | Request/Response |
|--------|------|---------|------------------|
| GET | `/prjxp/tools/projects` | List all projects (extended in Phase 06) | → `List<ProjectInfo>` |
| POST | `/prjxp/projects` | Create new project (Phase 06) | `ProjectCreateRequest` → `ProjectInfo` |
| PUT | `/prjxp/projects/{name}` | Update project config (Phase 06) | `ProjectUpdateRequest` → `ProjectInfo` |
| DELETE | `/prjxp/projects/{name}` | Delete project (existing) | → 204 No Content |
| POST | `/prjxp/projects/{name}/reindex` | Re-index project (existing) | → 204 No Content |
| GET | `/prjxp/projects/{name}/stats` | Get project statistics (Phase 06) | → `ProjectStats` |

### PrjxpYamlConfig Model (TypeScript)
Matches the Java `ProjectDefinition` fields:
```typescript
export interface PrjxpYamlConfig {
  name: string;
  rootDir?: string;           // default "."
  jsonlFile?: string;         // default "px-chunks.jsonl"
  chunoWhiteList?: string;    // default "java,ts"
  tibedBatchSize?: number;    // default 32
  embeddingsFile?: string;     // nullable, for pre-computed embeddings
  tibedResetStore?: boolean;  // default false
}

// Default values for new projects
export const DEFAULT_YAML_CONFIG: Omit<PrjxpYamlConfig, 'name'> = {
  rootDir: '.',
  jsonlFile: 'px-chunks.jsonl',
  chunoWhiteList: 'java,ts',
  tibedBatchSize: 32,
  embeddingsFile: undefined,
  tibedResetStore: false
};
```

### ProjectUpdateRequest Model
```typescript
export interface ProjectUpdateRequest {
  rootDir?: string;
  chunoWhiteList?: string;
  tibedBatchSize?: number;
}
```

### ProjectStats Model
```typescript
export interface ProjectStats {
  name: string;
  chunkCount: number;
  embeddingCount: number;
  fileCount: number;
  rootDir: string;
  languages: string[];
}
```

### ProjectManagementService Interface
```typescript
@Injectable({ providedIn: 'root' })
export class ProjectManagementService {
  constructor(private http: HttpClient) {}

  // List all projects (with stats from Phase 06)
  getProjects(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>('/prjxp/tools/projects');
  }

  // Create a new project (from YAML config)
  createProject(config: PrjxpYamlConfig): Observable<ProjectInfo> {
    const request: ProjectCreateRequest = {
      name: config.name,
      kind: 'LIVE',  // or SNAPSHOT based on context
      rootDir: config.rootDir,
      chunoWhiteList: config.chunoWhiteList,
      tibedBatchSize: config.tibedBatchSize
    };
    return this.http.post<ProjectInfo>('/prjxp/projects', request);
  }

  // Update an existing project's config
  updateProject(name: string, updates: ProjectUpdateRequest): Observable<ProjectInfo> {
    return this.http.put<ProjectInfo>(`/prjxp/projects/${name}`, updates);
  }

  // Delete a project
  deleteProject(name: string): Observable<HttpEvent<void>> {
    return this.http.delete(`/prjxp/projects/${name}`, { observe: 'events' });
  }

  // Re-index a project
  reindexProject(name: string): Observable<HttpEvent<void>> {
    return this.http.post(`/prjxp/projects/${name}/reindex`, null, { observe: 'events' });
  }

  // Get detailed stats for a project
  getProjectStats(name: string): Observable<ProjectStats> {
    return this.http.get<ProjectStats>(`/prjxp/projects/${name}/stats`);
  }
}
```

## 3. Implementation Details

### 3.1 Projects Component (Main View)
Two-column layout:
- **Left**: Project list with search/filter
- **Right**: Selected project detail card with actions (Edit, Reindex, Delete)

```typescript
@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [ProjectDetailCardComponent, YamlEditorDialogComponent, CommonModule],
  templateUrl: './projects.component.html',
  styleUrls: ['./projects.component.css']
})
export class ProjectsComponent {
  projects = signal<ProjectInfo[]>([]);
  selectedProject = signal<ProjectInfo | null>(null);
  searchFilter = signal('');

  constructor(private projectService: ProjectManagementService, private dialog: DialogService) {
    this.loadProjects();
  }

  loadProjects(): void {
    this.projectService.getProjects().subscribe(projects => this.projects.set(projects));
  }

  selectProject(project: ProjectInfo): void {
    this.selectedProject.set(project);
  }

  openEditor(project?: ProjectInfo): void {
    this.dialog.openYamlEditor(project).subscribe(config => {
      if (config) {
        // Create or update based on whether project exists
        const existing = this.projects().find(p => p.name === config.name);
        if (existing) {
          this.projectService.updateProject(config.name, {
            rootDir: config.rootDir,
            chunoWhiteList: config.chunoWhiteList,
            tibedBatchSize: config.tibedBatchSize
          }).subscribe(() => this.loadProjects());
        } else {
          this.projectService.createProject(config).subscribe(() => this.loadProjects());
        }
      }
    });
  }

  deleteProject(name: string): void {
    if (confirm(`Delete project "${name}"?`)) {
      this.projectService.deleteProject(name).subscribe(() => {
        this.loadProjects();
        if (this.selectedProject()?.name === name) this.selectedProject.set(null);
      });
    }
  }

  reindexProject(name: string): void {
    this.projectService.reindexProject(name).subscribe(() => {
      // Show success notification (TODO: add toast service)
    });
  }

  filteredProjects(): ProjectInfo[] {
    const filter = this.searchFilter().toLowerCase();
    if (!filter) return this.projects();
    return this.projects().filter(p => p.name.toLowerCase().includes(filter));
  }
}
```

### 3.2 YamlEditorDialog Component
Modal dialog with form fields for each `PrjxpYamlConfig` property:
- **Name** (required, readonly if editing)
- **Root Directory** (text input, default `.`)
- **JSONL File** (text input, default `px-chunks.jsonl`)
- **File Extensions** (tag input for chunoWhiteList, e.g., `java`, `ts`)
- **Batch Size** (number input, default 32)
- **Embeddings File** (optional file path)
- **Reset Store** (checkbox for tibedResetStore)

Form uses ReactiveForms with validation.

### 3.3 ProjectDetailCard Component
Detailed view of selected project:
- Name, kind (LIVE/SNAPSHOT), status badge
- Chunk count, embedding count, file count
- Actions: Edit (opens YAML editor), Reindex, Delete
- Status color coding: READY=green, FAILED=red, others=cyan

## 4. Tests (TDD)

### ProjectManagementService Tests
- `getProjects()` should GET `/prjxp/tools/projects`
- `createProject()` should POST to `/prjxp/projects` with correct request body
- `updateProject()` should PUT to `/prjxp/projects/{name}` with updates
- `deleteProject()` should DELETE `/prjxp/projects/{name}`
- `reindexProject()` should POST to `/prjxp/projects/{name}/reindex`
- `getProjectStats()` should GET `/prjxp/projects/{name}/stats`

### ProjectsComponent Tests
- `loadProjects()` should call getProjects() and update signal
- `selectProject()` should update selectedProject signal
- `openEditor()` should open dialog and call create/update on save
- `deleteProject()` should confirm and call deleteProject()
- `filteredProjects()` should filter by searchFilter

### YamlEditorDialog Tests
- Should pre-fill form fields when editing existing project
- Name field should be readonly when editing
- Submit should emit PrjxpYamlConfig with all fields
- Cancel should close dialog without emitting

### ProjectDetailCard Tests
- Should display project name, kind, status with correct colors
- Action buttons should emit edit/reindex/delete events
- Status badge should show correct color based on status enum

## 5. Acceptance Criteria
- [ ] Project list displays all projects with search/filter functionality
- [ ] YAML editor dialog allows creating and editing project configurations
- [ ] Edit button opens pre-filled form with existing values
- [ ] Delete/reindex actions work with confirmation dialogs
- [ ] All tests pass with >= 80% coverage
