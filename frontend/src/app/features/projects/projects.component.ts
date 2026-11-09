import { Component, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { ProjectDetailCardComponent } from '../../components/project-detail-card/project-detail-card.component';
import { YamlEditorDialogComponent } from '../../components/yaml-editor-dialog/yaml-editor-dialog.component';
import { GithubImportDialogComponent } from '../../components/github-import-dialog/github-import-dialog.component';
import { ReindexConfirmationDialogComponent } from '../../components/reindex-confirmation-dialog/reindex-confirmation-dialog.component';
import { LiveUpdateIndicatorComponent } from '../../components/live-update-indicator/live-update-indicator.component';
import { ToastContainerComponent } from '../../components/toast-container/toast-container.component';
import { ProjectManagementService } from '../../services/project-management.service';
import { ToastService } from '../../services/toast.service';
import { ProjectInfo } from '../../models/project-info.model';
import { PrjxpYamlConfig } from '../../models/prjxp-yaml-config.model';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [ProjectDetailCardComponent, YamlEditorDialogComponent, GithubImportDialogComponent, ReindexConfirmationDialogComponent, LiveUpdateIndicatorComponent, ToastContainerComponent],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.css'
})
export class ProjectsComponent implements OnInit, OnDestroy {
  projects = signal<ProjectInfo[]>([]);
  selectedProjectName = signal<string | null>(null);
  searchFilter = signal('');
  showEditor = signal(false);
  editingProjectName = signal<string | null>(null);

  // Reindex confirmation dialog state
  showReindexConfirm = signal(false);
  reindexProjectName = signal<string | null>(null);

  // GitHub import dialog state
  showGithubImport = signal(false);
  // Operation feedback for the import dialog (driven by the github-operations poll)
  dialogStatus = signal<'idle' | 'running' | 'success' | 'error'>('idle');
  dialogMessage = signal<string | null>(null);

  // Polling for reindex progress
  private pollingInterval: ReturnType<typeof setInterval> | null = null;
  // Periodic list refresh so the live-update indicator stays fresh (backend is the source of truth)
  private listRefreshInterval: ReturnType<typeof setInterval> | null = null;
  // Polling for the async GitHub operation (import/update) — the tracker is the source of truth
  private githubPollTimer: ReturnType<typeof setInterval> | null = null;
  private githubPollDeadline = 0;

  // Derived signals - always get fresh objects from the projects list
  selectedProject = computed(() => {
    const name = this.selectedProjectName();
    return name ? this.projects().find(p => p.name === name) ?? null : null;
  });

  editingProject = computed(() => {
    const name = this.editingProjectName();
    return name ? this.projects().find(p => p.name === name) ?? null : null;
  });

  constructor(
    private projectService: ProjectManagementService,
    private toastService: ToastService
  ) {}

  ngOnInit(): void {
    this.loadProjects();
    // Refresh the list periodically so live-update status (and counts) stay current
    this.listRefreshInterval = setInterval(() => this.loadProjects(), 10000);
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.stopGithubPolling();
    if (this.listRefreshInterval !== null) {
      clearInterval(this.listRefreshInterval);
      this.listRefreshInterval = null;
    }
  }

  loadProjects(): void {
    this.projectService.getProjects().subscribe(projects => {
      this.projects.set(projects);
      // Resume polling for a project with an active reindex (e.g. after a page reload)
      const active = projects.find(p => this.isReindexActive(p));
      if (active) {
        this.startPolling(active.name);
      }
    });
  }

  selectProject(project: ProjectInfo): void {
    this.selectedProjectName.set(project.name);
  }

  onEditProject(): void {
    const selected = this.selectedProject();
    if (selected) {
      this.editingProjectName.set(selected.name);
      this.showEditor.set(true);
    }
  }

  onCreateProject(): void {
    this.editingProjectName.set(null);
    this.showEditor.set(true);
  }

  onYamlSave(config: PrjxpYamlConfig): void {
    const existing = this.projects().find(p => p.name === config.name);
    if (existing) {
      this.projectService.updateProject(config.name, {
        rootDir: config.rootDir,
        chunoWhiteList: config.chunoWhiteList,
        tibedBatchSize: config.tibedBatchSize,
        prompt: config.prompt ?? null,
        fulltext: config.fulltext,
        embedDependencies: config.embedDependencies
      }).subscribe(() => {
        this.loadProjects();
        this.showEditor.set(false);
      });
    } else {
      this.projectService.createProject(config).subscribe(() => {
        this.loadProjects();
        this.showEditor.set(false);
      });
    }
  }

  onYamlClose(): void {
    this.showEditor.set(false);
  }

  // ------------------------------------------------------------------ Reindex flow

  onReindexConfirm(project: ProjectInfo): void {
    this.reindexProjectName.set(project.name);
    this.showReindexConfirm.set(true);
  }

  onReindexConfirmed(): void {
    const name = this.reindexProjectName();
    if (name) {
      this.showReindexConfirm.set(false);
      this.projectService.reindexProject(name).subscribe(() => {
        // Start polling for reindex status
        this.startPolling(name);
      });
    }
  }

  onReindexCancelled(): void {
    this.showReindexConfirm.set(false);
    this.reindexProjectName.set(null);
  }

  // ------------------------------------------------------------------ DocPipe flow

  onDocPipe(): void {
    const selected = this.selectedProject();
    if (selected) {
      this.projectService.docpipeProject(selected.name).subscribe(() => {
        // Start polling for doc phase status
        this.startPolling(selected.name);
      });
    }
  }

  // ------------------------------------------------------------------ Embed Deps / Reimport flow

  onEmbedDeps(): void {
    const selected = this.selectedProject();
    if (selected) {
      this.projectService.embedDependencies(selected.name).subscribe(() => {
        this.loadProjects();   // refresh once the (async) materialization registers the libraries
      });
    }
  }

  onReimport(): void {
    const selected = this.selectedProject();
    if (selected) {
      this.projectService.reimportProject(selected.name).subscribe(() => {
        this.loadProjects();
      });
    }
  }

  // ------------------------------------------------------------------ GitHub import / update flow

  onImportGithubClick(): void {
    this.showGithubImport.set(true);
  }

  onImportGithub(creds: { url: string; token: string }): void {
    // The dialog stays open — it shows the operation status (running → success/error)
    this.dialogStatus.set('running');
    this.dialogMessage.set(null);
    this.projectService.importGithub(creds.url, creds.token).subscribe({
      next: () => {
        this.loadProjects();   // the new project appears as IMPORTING
        this.pollGithubOperation(creds.url, (ok, message) => {
          if (ok) {
            this.dialogStatus.set('success');
            this.dialogMessage.set(message);
            setTimeout(() => {
              if (this.showGithubImport()) {
                this.showGithubImport.set(false);
                this.dialogStatus.set('idle');
                this.dialogMessage.set(null);
              }
              this.loadProjects();
            }, 1500);
          } else {
            this.dialogStatus.set('error');
            this.dialogMessage.set(message);   // the dialog stays open — retryable
          }
        });
      },
      error: (err: any) => {
        this.dialogStatus.set('error');
        this.dialogMessage.set(err?.error?.message ?? err?.message ?? 'Import request failed');
      }
    });
  }

  onGithubImportClose(): void {
    this.stopGithubPolling();
    this.dialogStatus.set('idle');
    this.dialogMessage.set(null);
    this.showGithubImport.set(false);
  }

  onUpdateGithub(): void {
    const selected = this.selectedProject();
    if (selected) {
      this.projectService.updateGithub(selected.name).subscribe(() => {
        this.loadProjects();
        this.pollGithubOperation(selected.name, (ok, message) => {
          if (ok) {
            this.toastService.success(message ?? 'Updated');
          } else {
            this.toastService.error(message ?? 'Update failed');
          }
        });
      });
    }
  }

  // ------------------------------------------------------------------ Polling for the async GitHub operation

  /**
   * Polls the github-operations endpoint every 2 s until the operation with {@code key} resolves
   * (or the 300 s deadline elapses — covers the server-side githubTimeoutSeconds default).
   * Transient poll failures keep polling.
   */
  pollGithubOperation(key: string, onDone: (ok: boolean, message: string | null) => void): void {
    this.stopGithubPolling();
    this.githubPollDeadline = Date.now() + 300_000;
    this.githubPollTimer = setInterval(() => {
      this.projectService.getGithubOperations().subscribe({
        next: ops => {
          const op = ops.find(o => o.key === key);
          if (op && op.status !== 'PENDING') {
            this.stopGithubPolling();
            onDone(op.status === 'SUCCESS', op.message);
          } else if (Date.now() > this.githubPollDeadline) {
            this.stopGithubPolling();
            onDone(false, 'Timed out waiting for the GitHub operation');
          }
        },
        error: () => {
          // transient poll failure — keep polling
        }
      });
    }, 2000);
  }

  stopGithubPolling(): void {
    if (this.githubPollTimer !== null) {
      clearInterval(this.githubPollTimer);
      this.githubPollTimer = null;
    }
  }

  // ------------------------------------------------------------------ Polling for reindex progress

  startPolling(projectName: string): void {
    this.stopPolling();

    this.pollingInterval = setInterval(() => {
      this.projectService.getReindexStatus(projectName).subscribe(status => {
        // Update the project in the list with new status & progress
        this.projects.update(projects => {
          return projects.map(p => {
            if (p.name === projectName) {
              return { ...p, reindexStatus: status.status, reindexProgress: status.progress };
            }
            return p;
          });
        });

        // Stop polling when complete or failed
        if (status.status === 'COMPLETE' || status.status === 'FAILED') {
          this.stopPolling();
          this.loadProjects();   // refresh counts/status after the pipeline finished

          // Reset status after 5 seconds so the UI shows clean state
          setTimeout(() => {
            this.projects.update(projects => {
              return projects.map(p => {
                if (p.name === projectName) {
                  return { ...p, reindexStatus: 'IDLE', reindexProgress: 0 };
                }
                return p;
              });
            });
          }, 5000);
        }
      });
    }, 2000); // Poll every 2 seconds
  }

  stopPolling(): void {
    if (this.pollingInterval !== null) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
    }
  }

  // ------------------------------------------------------------------ Delete

  onDeleteProject(name: string): void {
    this.projectService.deleteProject(name).subscribe(() => {
      this.loadProjects();
      if (this.selectedProjectName() === name) {
        this.selectedProjectName.set(null);
      }
    });
  }

  // ------------------------------------------------------------------ Filtering & helpers

  filteredProjects(): ProjectInfo[] {
    const filter = this.searchFilter().toLowerCase();
    if (!filter) return this.projects();
    return this.projects().filter(p => p.name.toLowerCase().includes(filter));
  }

  setSearchFilter(value: string): void {
    this.searchFilter.set(value);
  }

  getStatusBadgeClass(status: string): string {
    switch (status) {
      case 'READY': return 'status-ready';
      case 'FAILED': return 'status-failed';
      default: return 'status-default';
    }
  }

  isReindexActive(project: ProjectInfo): boolean {
    const status = project.reindexStatus;
    return status === 'CHUNKING' || status === 'EMBEDDING' || status === 'IMPORTING' || status === 'DOCPIPE';
  }
}
