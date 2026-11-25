import { TestBed } from '@angular/core/testing';
import { provideMarkdown } from 'ngx-markdown';
import { of } from 'rxjs';
import { DocsComponent } from './docs.component';
import { DocsService } from './docs.service';
import { DocsNode } from './docs.model';
import { vi } from 'vitest';

describe('DocsComponent', () => {
  let component: DocsComponent;
  let fixture: any;
  let mockDocs: { getTree: ReturnType<typeof vi.fn>; getContent: ReturnType<typeof vi.fn>; fileUrl: ReturnType<typeof vi.fn> };

  const readmeTree: DocsNode = {
    name: 'doc',
    type: 'root',
    children: [{ name: 'README', type: 'doc', path: 'README.md' }]
  };

  const noReadmeTree: DocsNode = {
    name: 'doc',
    type: 'root',
    children: [
      {
        name: 'guide',
        type: 'folder',
        children: [
          { name: 'intro', type: 'doc', path: 'guide/intro.md' },
          { name: 'advanced', type: 'doc', path: 'guide/advanced.md' }
        ]
      },
      { name: 'reference', type: 'doc', path: 'reference.md' }
    ]
  };

  beforeEach(async () => {
    mockDocs = {
      getTree: vi.fn().mockReturnValue(of(readmeTree)),
      getContent: vi.fn().mockReturnValue(of('# Content')),
      fileUrl: vi.fn((p: string) => `/api/docs/file?path=${encodeURIComponent(p)}`)
    };

    await TestBed.configureTestingModule({
      imports: [DocsComponent],
      providers: [
        provideMarkdown(),
        { provide: DocsService, useValue: mockDocs }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(DocsComponent);
    component = fixture.componentInstance;
  });

  it('ngOnInit should call getTree', () => {
    component.ngOnInit();
    expect(mockDocs.getTree).toHaveBeenCalled();
  });

  it('should select the root README.md when the tree contains one', () => {
    component.ngOnInit();
    expect(mockDocs.getContent).toHaveBeenCalledWith('README.md');
    expect(component.selectedPath()).toBe('README.md');
    expect(component.showIndex()).toBe(false);
  });

  it('should show the index view when the tree has no README', () => {
    mockDocs.getTree.mockReturnValue(of(noReadmeTree));
    component.ngOnInit();
    expect(component.showIndex()).toBe(true);
    expect(component.indexEntries()).toEqual([
      { label: 'guide / intro', path: 'guide/intro.md' },
      { label: 'guide / advanced', path: 'guide/advanced.md' },
      { label: 'reference', path: 'reference.md' }
    ]);
    fixture.detectChanges();
    const links = Array.from(fixture.nativeElement.querySelectorAll('.index-list a')) as HTMLElement[];
    expect(links.map(a => a.textContent)).toEqual(['guide / intro', 'guide / advanced', 'reference']);
  });

  it('selectDoc should store the rewritten content', () => {
    mockDocs.getContent.mockReturnValue(of('x'));
    component.selectDoc('a.md');
    expect(mockDocs.getContent).toHaveBeenCalledWith('a.md');
    expect(component.content()).toBe('x');
    expect(component.selectedPath()).toBe('a.md');
    expect(component.showIndex()).toBe(false);
  });

  it('refresh should re-request the tree and the current content', () => {
    component.ngOnInit(); // getTree #1, getContent(README.md) #1
    component.refresh();
    expect(mockDocs.getTree).toHaveBeenCalledTimes(2);
    expect(mockDocs.getContent).toHaveBeenLastCalledWith('README.md');
  });

  it('rewriteAssetUrls should rewrite relative URLs and leave external, absolute, anchor and .md links unchanged', () => {
    const md = [
      '<img src="images/a.png">',
      '<a href="http://example.com">ext</a>',
      '<a href="/abs">abs</a>',
      '<a href="#anchor">anchor</a>',
      '<a href="other.md">md</a>'
    ].join('\n');
    const out = component.rewriteAssetUrls(md, 'guide/doc.md');
    expect(out).toContain('src="/api/docs/file?path=guide%2Fimages%2Fa.png"');
    expect(out).toContain('<a href="http://example.com">ext</a>');
    expect(out).toContain('<a href="/abs">abs</a>');
    expect(out).toContain('<a href="#anchor">anchor</a>');
    expect(out).toContain('<a href="other.md">md</a>');
  });

  it('onContentClick should navigate for relative .md links and ignore external links', () => {
    component.selectedPath.set('guide/doc.md');

    const link = document.createElement('a');
    link.setAttribute('href', 'other.md');
    const event = new MouseEvent('click');
    Object.defineProperty(event, 'target', { value: link });
    component.onContentClick(event);
    expect(mockDocs.getContent).toHaveBeenCalledTimes(1);
    expect(mockDocs.getContent).toHaveBeenCalledWith('guide/other.md');

    const extLink = document.createElement('a');
    extLink.setAttribute('href', 'https://example.com');
    const extEvent = new MouseEvent('click');
    Object.defineProperty(extEvent, 'target', { value: extLink });
    component.onContentClick(extEvent);
    expect(mockDocs.getContent).toHaveBeenCalledTimes(1); // unchanged
  });

  it('should render the markdown content and switch to the error state', () => {
    component.content.set('# Hello');
    fixture.detectChanges();
    const md = fixture.nativeElement.querySelector('.doc-content markdown');
    expect(md).toBeTruthy();

    component.error.set('boom');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.doc-state')?.textContent).toContain('boom');
  });

  it('markHeadings should assign deduplicated ids to H2 headings', () => {
    const host = fixture.nativeElement;
    const content = document.createElement('div');
    content.className = 'doc-content';
    content.innerHTML = '<h2>Setup</h2><h2>Setup</h2><h2>Other</h2>';
    host.appendChild(content);

    component.markHeadings();

    const ids = Array.from(content.querySelectorAll('h2')).map(h => h.id);
    expect(ids).toEqual(['setup', 'setup-2', 'other']);
  });
});
