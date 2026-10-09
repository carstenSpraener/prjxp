import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProjectInfo } from '../models/project-info.model';
import { LiveProjectCandidate, ProjectCreateRequest } from '../models/live-project-candidate.model';
import { API_BASE } from '../config/api-config';

@Injectable({ providedIn: 'root' })
export class ImportService {
  private readonly baseUrl = API_BASE;

  constructor(private http: HttpClient) {}

  uploadArchive(file: File): Observable<ProjectInfo> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<ProjectInfo>(`${this.baseUrl}/projects/upload-archive`, formData);
  }

  uploadEmbedded(file: File): Observable<ProjectInfo> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<ProjectInfo>(`${this.baseUrl}/projects/upload-embedded`, formData);
  }

  scanLiveProjects(): Observable<LiveProjectCandidate[]> {
    return this.http.get<LiveProjectCandidate[]>(`${this.baseUrl}/live-scan`);
  }

  registerProject(request: ProjectCreateRequest): Observable<ProjectInfo> {
    return this.http.post<ProjectInfo>(`${this.baseUrl}/projects`, request);
  }

  importEmbeddings(projectName: string, file: File): Observable<HttpEvent<any>> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post(`${this.baseUrl}/projects/${projectName}/importEmbeddings`, formData, {
      observe: 'events'
    });
  }
}
