# Phase 03: Import Module

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans.
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document.
> 4. **MANDATORY FINAL STEP:** Execute `/compact` IMMEDIATELY after green tests (>= 80% coverage).

## 1. Target & Scope
* **Objective:** Build the import workflow with two modes: (a) TAR/ZIP file upload and (b) Live directory monitoring with auto-discovery.
* **Target Files to Create/Modify:**
    * Modify: `frontend/src/app/app.routes.ts` - Replace placeholder for `/import` route
    * Create: `frontend/src/app/features/import/import.component.ts`
    * Create: `frontend/src/app/features/import/import.component.html`
    * Create: `frontend/src/app/features/import/import.component.css`
    * Create: `frontend/src/app/features/import/import.component.spec.ts`
    * Create: `frontend/src/app/services/import.service.ts`
    * Create: `frontend/src/app/services/import.service.spec.ts`
    * Create: `frontend/src/app/models/live-project-candidate.model.ts`
    * Create: `frontend/src/app/components/file-upload-zone/file-upload-zone.component.ts`
    * Create: `frontend/src/app/components/file-upload-zone/file-upload-zone.component.html`
    * Create: `frontend/src/app/components/file-upload-zone/file-upload-zone.component.css`
    * Create: `frontend/src/app/components/file-upload-zone/file-upload-zone.component.spec.ts`
    * Create: `frontend/src/app/components/project-list-card/project-list-card.component.ts`
    * Create: `frontend/src/app/components/project-list-card/project-list-card.component.html`
    * Create: `frontend/src/app/components/project-list-card/project-list-card.component.css`

## 2. Inline Required Context

### REST Endpoints (from mcp-server:7007)
| Method | Path | Purpose | Request/Response |
|--------|------|---------|------------------|
| POST | `/prjxp/projects/upload` | Upload TAR/ZIP archive (Phase 06) | Multipart: `file` → `ProjectInfo` |
| GET | `/prjxp/live-scan` | Scan for live project directories (Phase 06) | → `List<LiveProjectCandidate>` |
| POST | `/prjxp/projects` | Register a discovered project (Phase 06) | `ProjectCreateRequest` → `ProjectInfo` |
| POST | `/prjxp/projects/{name}/importEmbeddings` | Import pre-computed embeddings (existing) | Multipart: `file` → 204 |

### LiveProjectCandidate Model
```typescript
export interface LiveProjectCandidate {
  name: string;
  directory: string;
  hasConfigFile: boolean;   // prjxp.yaml/.yml exists?
  languages: string[];      // detected file extensions (e.g., ['java', 'ts'])
}
```

### ProjectCreateRequest Model
```typescript
export interface ProjectCreateRequest {
  name: string;
  kind: 'LIVE' | 'SNAPSHOT';
  rootDir?: string;         // for LIVE: directory path; for SNAPSHOT: tar/zip path
  chunoWhiteList?: string;  // default "java,ts"
  tibedBatchSize?: number;  // default 32
}
```

### ImportService Interface
```typescript
@Injectable({ providedIn: 'root' })
export class ImportService {
  constructor(private http: HttpClient) {}

  // Upload a TAR/ZIP file for import
  uploadArchive(file: File): Observable<ProjectInfo> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<ProjectInfo>('/prjxp/projects/upload', formData);
  }

  // Scan directories for live project candidates
  scanLiveProjects(): Observable<LiveProjectCandidate[]> {
    return this.http.get<LiveProjectCandidate[]>('/prjxp/live-scan');
  }

  // Register a discovered project
  registerProject(request: ProjectCreateRequest): Observable<ProjectInfo> {
    return this.http.post<ProjectInfo>('/prjxp/projects', request);
  }

  // Import pre-computed embeddings for an existing project
  importEmbeddings(projectName: string, file: File): Observable<HttpEvent<void>> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post(`/prjxp/projects/${projectName}/importEmbeddings`, formData, {
      observe: 'events'
    });
  }
}
```

## 3. Implementation Details

### 3.1 Import Component (Main View)
Two-tab layout:
- **Tab 1: "Upload Archive"** - File upload zone for TAR/ZIP files
- **Tab 2: "Live Projects"** - Directory scanner with register buttons

```typescript
@Component({
  selector: 'app-import',
  standalone: true,
  imports: [FileUploadZoneComponent, ProjectListCardComponent, CommonModule],
  templateUrl: './import.component.html',
  styleUrls: ['./import.component.css']
})
export class ImportComponent {
  activeTab = signal<'upload' | 'live'>('upload');
  uploadedProjects = signal<ProjectInfo[]>([]);
  liveCandidates = signal<LiveProjectCandidate[]>([]);
  isScanning = signal(false);

  constructor(private importService: ImportService) {}

  onFileSelected(file: File): void {
    this.importService.uploadArchive(file).subscribe(project => {
      this.uploadedProjects.update(projects => [...projects, project]);
    });
  }

  scanForLiveProjects(): void {
    this.isScanning.set(true);
    this.importService.scanLiveProjects().subscribe({
      next: candidates => {
        this.liveCandidates.set(candidates);
        this.isScanning.set(false);
      },
      error: () => this.isScanning.set(false)
    });
  }

  registerProject(candidate: LiveProjectCandidate): void {
    const request: ProjectCreateRequest = {
      name: candidate.name,
      kind: 'LIVE',
      rootDir: candidate.directory
    };
    this.importService.registerProject(request).subscribe(project => {
      // Remove from candidates list
      this.liveCandidates.update(cands => cands.filter(c => c.name !== candidate.name));
    });
  }
}
```

### 3.2 FileUploadZone Component
Drag-and-drop zone with cyberpunk styling:
- Dashed border with neon glow on hover/drag-over
- Accepts `.tar`, `.tar.gz`, `.tgz`, `.zip` files
- Shows file name and size after selection

### 3.3 ProjectListCard Component
Card for each live project candidate:
- Shows name, directory path, detected languages (badges)
- "Register" button with neon glow effect
- Config file indicator (✓ if `hasConfigFile` is true)

## 4. Tests (TDD)

### ImportService Tests
- `uploadArchive()` should send FormData with 'file' key to `/prjxp/projects/upload`
- `scanLiveProjects()` should GET `/prjxp/live-scan` and return array
- `registerProject()` should POST to `/prjxp/projects` with request body
- `importEmbeddings()` should send FormData to `/prjxp/projects/{name}/importEmbeddings`

### ImportComponent Tests
- Should switch between upload and live tabs
- `onFileSelected()` should call importService.uploadArchive()
- `scanForLiveProjects()` should set isScanning and call scanLiveProjects()
- `registerProject()` should call registerProject() with correct request

### FileUploadZone Tests
- Should accept only valid file types (tar, tar.gz, tgz, zip)
- Should emit selected file event
- Should show drag-over visual feedback

### ProjectListCard Tests
- Should display project name, directory, and language badges
- "Register" button should emit register event with candidate data

## 5. Acceptance Criteria
- [ ] Upload zone accepts TAR/ZIP files with drag-and-drop
- [ ] Live project scan shows discovered directories with language badges
- [ ] Register button adds project and removes from candidate list
- [ ] All tests pass with >= 80% coverage
