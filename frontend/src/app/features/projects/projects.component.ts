import { Component, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { ProjectDetailCardComponent } from '../../components/project-detail-card/project-detail-card.component';
import { YamlEditorDialogComponent } from '../../components/yaml-editor-dialog/yaml-editor-dialog.component';
import { ReindexConfirmationDialogComponent } from '../../components/reindex-confirmation-dialog/reindex-confirmation-dialog.component';
import { ProjectManagementService } from '../../services/project-management.service';
import { ProjectInfo } from '../../models/project-info.model';
import { PrjxpYamlConfig } from '../../models/prjxp-yaml-config.model';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [ProjectDetailCardComponent, YamlEditorDialogComponent, ReindexConfirmationDialogComponent],
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

  // Polling for reindex progress
  private pollingInterval: ReturnType<typeof setInterval> | null = null;

  // Derived signals - always get fresh objects from the projects list
  selectedProject = computed(() => {
    const name = this.selectedProjectName();
    return name ? this.projects().find(p => p.name === name) ?? null : null;
  });

  editingProject = computed(() => {
    const name = this.editingProjectName();
    return name ? this.projects().find(p => p.name === name) ?? null : null;
  });

  constructor(private projectService: ProjectManagementService) {}

  ngOnInit(): void {
    this.loadProjects();
  }

  ngOnDestroy(): void {
    this.stopPolling();
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
        fulltext: config.fulltext
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
