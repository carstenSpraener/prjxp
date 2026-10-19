import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProjectInfo } from '../models/project-info.model';
import { PrjxpYamlConfig, ProjectUpdateRequest, ProjectStats, ProjectCreateRequest } from '../models/prjxp-yaml-config.model';
import { API_BASE } from '../config/api-config';

@Injectable({ providedIn: 'root' })
export class ProjectManagementService {
  private readonly baseUrl = API_BASE;

  constructor(private http: HttpClient) {}

  getProjects(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>(`${this.baseUrl}/tools/projects`);
  }

  createProject(config: PrjxpYamlConfig): Observable<ProjectInfo> {
    const request: ProjectCreateRequest = {
      name: config.name,
      kind: 'LIVE',
      rootDir: config.rootDir,
      chunoWhiteList: config.chunoWhiteList,
      tibedBatchSize: config.tibedBatchSize
    };
    return this.http.post<ProjectInfo>(`${this.baseUrl}/projects`, request);
  }

  updateProject(name: string, updates: ProjectUpdateRequest): Observable<ProjectInfo> {
    return this.http.put<ProjectInfo>(`${this.baseUrl}/projects/${name}`, updates);
  }

  deleteProject(name: string): Observable<HttpEvent<any>> {
    return this.http.delete(`${this.baseUrl}/projects/${name}`, { observe: 'events' });
  }

  reindexProject(name: string): Observable<HttpEvent<any>> {
    return this.http.post(`${this.baseUrl}/projects/${name}/reindex`, null, { observe: 'events' });
  }

  docpipeProject(name: string): Observable<HttpEvent<any>> {
    return this.http.post(`${this.baseUrl}/projects/${name}/docpipe`, null, { observe: 'events' });
  }

  getReindexStatus(projectName: string): Observable<{ status: string; progress: number }> {
    return this.http.get<{ status: string; progress: number }>(`${this.baseUrl}/projects/${projectName}/reindex-status`);
  }

  getProjectStats(name: string): Observable<ProjectStats> {
    return this.http.get<ProjectStats>(`${this.baseUrl}/projects/${name}/stats`);
  }
}
