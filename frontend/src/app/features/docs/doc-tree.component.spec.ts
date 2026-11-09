import { TestBed } from '@angular/core/testing';
import { DocTreeComponent } from './doc-tree.component';
import { DocsNode } from './docs.model';
import { vi } from 'vitest';

describe('DocTreeComponent', () => {
  let component: DocTreeComponent;
  let fixture: any;

  const sampleNode: DocsNode = {
    name: 'docs-root',
    type: 'root',
    children: [
      {
        name: 'guide',
        type: 'folder',
        children: [
          {
            name: 'intro',
            type: 'doc',
            path: 'guide/intro.md',
            chapters: [
              { title: 'First Chapter', anchor: 'first-chapter' },
              { title: 'Second Chapter', anchor: 'second-chapter' }
            ]
          }
        ]
      },
      { name: 'standalone', type: 'doc', path: 'standalone.md' }
    ]
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DocTreeComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(DocTreeComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('node', sampleNode);
    fixture.componentRef.setInput('selectedPath', null);
    fixture.detectChanges();
  });

  function clickRow(selector: string): void {
    (fixture.nativeElement.querySelector(selector) as HTMLElement).click();
    fixture.detectChanges();
  }

  it('should render folder and doc labels', () => {
    // Expand the folder so the nested doc is rendered as well
    clickRow('.tree-row.folder');
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('guide');
    expect(text).toContain('intro');
    expect(text).toContain('standalone');
  });

  it('should toggle folder children on click (hidden → visible)', () => {
    expect(fixture.nativeElement.querySelector('.children')).toBeNull();
    clickRow('.tree-row.folder');
    expect(fixture.nativeElement.querySelector('.children')).not.toBeNull();
    clickRow('.tree-row.folder');
    expect(fixture.nativeElement.querySelector('.children')).toBeNull();
  });

  it('should emit docSelected with the path when a doc is clicked', () => {
    const spy = vi.fn();
    component.docSelected.subscribe(spy);
    clickRow('.tree-row.doc');
    expect(spy).toHaveBeenCalledWith('standalone.md');
  });

  it('should emit chapterSelected with path and anchor when a chapter is clicked', () => {
    const spy = vi.fn();
    component.chapterSelected.subscribe(spy);
    clickRow('.tree-row.folder'); // expand so the chapters are rendered
    const chapter = fixture.nativeElement.querySelectorAll('.tree-row.chapter')[0] as HTMLElement;
    chapter.click();
    expect(spy).toHaveBeenCalledWith({ path: 'guide/intro.md', anchor: 'first-chapter' });
  });

  it('should mark the selected doc row as active', () => {
    fixture.componentRef.setInput('selectedPath', 'standalone.md');
    fixture.detectChanges();
    const rows = Array.from(fixture.nativeElement.querySelectorAll('.tree-row.doc')) as HTMLElement[];
    const active = rows.filter(r => r.classList.contains('active'));
    expect(active).toHaveLength(1);
    expect(active[0].textContent).toContain('standalone');
  });

  it('should render root children without a row for the root itself', () => {
    const text = fixture.nativeElement.textContent;
    expect(text).not.toContain('docs-root');
    expect(text).toContain('guide');
    expect(text).toContain('standalone');
  });
});
