import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpParams } from '@angular/common/http';
import { DocsService } from './docs.service';
import { vi } from 'vitest';

describe('DocsService', () => {
  let service: DocsService;
  let http: { get: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    http = { get: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        DocsService,
        { provide: HttpClient, useValue: http }
      ]
    });
    service = TestBed.inject(DocsService);
  });

  it('getTree should request a URL containing /docs/tree', () => {
    service.getTree();
    const url = http.get.mock.calls[0][0] as string;
    expect(url).toContain('/docs/tree');
  });

  it('getContent should request /docs/content with a path param and text response type', () => {
    service.getContent('a/b.md');
    const [url, options] = http.get.mock.calls[0] as [string, { params: HttpParams; responseType: string }];
    expect(url).toContain('/docs/content');
    expect(options.responseType).toBe('text');
    expect(options.params).toBeInstanceOf(HttpParams);
    expect(options.params.get('path')).toBe('a/b.md');
  });

  it('fileUrl should build an encoded file URL against the API base', () => {
    expect(service.fileUrl('images/a b.png')).toBe('/api/docs/file?path=images%2Fa%20b.png');
  });
});
