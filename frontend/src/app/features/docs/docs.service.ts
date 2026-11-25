import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../config/api-config';
import { DocsNode } from './docs.model';

@Injectable({ providedIn: 'root' })
export class DocsService {
  private readonly baseUrl = API_BASE;

  constructor(private http: HttpClient) {}

  getTree(): Observable<DocsNode> {
    return this.http.get<DocsNode>(`${this.baseUrl}/docs/tree`);
  }

  getContent(path: string): Observable<string> {
    return this.http.get(`${this.baseUrl}/docs/content`, {
      params: new HttpParams().set('path', path),
      responseType: 'text'
    });
  }

  /** URL for a static asset inside the docs root (images etc.). */
  fileUrl(path: string): string {
    return `${this.baseUrl}/docs/file?path=${encodeURIComponent(path)}`;
  }
}
