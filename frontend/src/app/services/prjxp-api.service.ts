import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ProjectInfo } from '../models/project-info.model';
import { API_BASE } from '../config/api-config';

@Injectable({ providedIn: 'root' })
export class PrjxpApiService {
  private readonly baseUrl = API_BASE;

  constructor(private http: HttpClient) {}

  ping(): Observable<string> {
    return this.http.get(`${this.baseUrl}/tools/ping`, { responseType: 'text' });
  }

  getProjects(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>(`${this.baseUrl}/tools/projects`);
  }

  deleteProject(name: string): Observable<HttpEvent<any>> {
    return this.http.delete(`${this.baseUrl}/projects/${name}`, { observe: 'events' });
  }

  reindexProject(name: string): Observable<HttpEvent<any>> {
    return this.http.post(`${this.baseUrl}/projects/${name}/reindex`, null, { observe: 'events' });
  }

  importEmbeddings(name: string, file: File): Observable<HttpEvent<any>> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post(`${this.baseUrl}/projects/${name}/importEmbeddings`, formData, { observe: 'events' });
  }

  vectorSearch(query: string, project?: string, limit: number = 20): Observable<any[]> {
    const params: Record<string, string | number> = { query, limit };
    if (project) params['project'] = project;
    return this.http.get<any[]>(`${this.baseUrl}/tools/vectorSearch`, { params });
  }

  grepSearch(pattern: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/tools/grep`, { params: { pattern } });
  }
}
