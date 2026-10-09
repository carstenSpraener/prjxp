import { Component, signal } from '@angular/core';
import { FileUploadZoneComponent } from '../../components/file-upload-zone/file-upload-zone.component';
import { ProjectListCardComponent } from '../../components/project-list-card/project-list-card.component';
import { ImportService } from '../../services/import.service';
import { ProjectInfo } from '../../models/project-info.model';
import { LiveProjectCandidate, ProjectCreateRequest } from '../../models/live-project-candidate.model';

@Component({
  selector: 'app-import',
  standalone: true,
  imports: [FileUploadZoneComponent, ProjectListCardComponent],
  templateUrl: './import.component.html',
  styleUrl: './import.component.css'
})
export class ImportComponent {
  activeTab = signal<'upload' | 'live' | 'embedded'>('upload');
  uploadedProjects = signal<ProjectInfo[]>([]);
  embeddedProjects = signal<ProjectInfo[]>([]);
  liveCandidates = signal<LiveProjectCandidate[]>([]);
  isScanning = signal(false);

  constructor(private importService: ImportService) {}

  onFileSelected(file: File): void {
    this.importService.uploadArchive(file).subscribe({
      next: project => {
        this.uploadedProjects.update(projects => [...projects, project]);
      },
      error: (err) => {
        console.error('Archive upload failed:', err);
        alert(`Upload fehlgeschlagen: ${err.message || err.error?.error || 'Unbekannter Fehler'}`);
      }
    });
  }

  onEmbeddedFileSelected(file: File): void {
    this.importService.uploadEmbedded(file).subscribe({
      next: project => {
        this.embeddedProjects.update(projects => [...projects, project]);
      },
      error: (err) => {
        console.error('Embedded upload failed:', err);
        const msg = err.error?.error || err.message || 'Unbekannter Fehler';
        alert(`Upload fehlgeschlagen: ${msg}`);
      }
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
    this.importService.registerProject(request).subscribe(() => {
      this.liveCandidates.update(cands => cands.filter(c => c.name !== candidate.name));
    });
  }
}
