import { Component, ElementRef, Injector, OnInit, afterNextRender, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MarkdownComponent } from 'ngx-markdown';
import { DocTreeComponent } from './doc-tree.component';
import { DocsService } from './docs.service';
import { DocsNode, slugify } from './docs.model';

@Component({
  selector: 'app-docs',
  standalone: true,
  imports: [CommonModule, DocTreeComponent, MarkdownComponent],
  templateUrl: './docs.component.html',
  styleUrl: './docs.component.css'
})
export class DocsComponent implements OnInit {
  tree = signal<DocsNode | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  selectedPath = signal<string | null>(null);
  content = signal<string | null>(null);
  showIndex = signal(false);

  private pendingAnchor: string | null = null;
  private readonly injector = inject(Injector);

  constructor(private docs: DocsService, private el: ElementRef) {}

  ngOnInit(): void {
    this.loadTree();
  }

  loadTree(): void {
    this.loading.set(true);
    this.error.set(null);
    this.docs.getTree().subscribe({
      next: (t) => {
        this.tree.set(t);
        this.loading.set(false);
        const readme = (t.children ?? []).find(c => c.type === 'doc' && c.path === 'README.md');
        if (readme?.path) this.selectDoc(readme.path);
        else this.showIndex.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load the documentation.');
      }
    });
  }

  selectDoc(path: string): void {
    this.showIndex.set(false);
    this.selectedPath.set(path);
    this.content.set(null);
    this.pendingAnchor = null;
    this.docs.getContent(path).subscribe({
      next: (md) => {
        this.content.set(this.rewriteAssetUrls(md, path));
        afterNextRender(() => this.markHeadings(), { injector: this.injector });
      },
      error: () => this.error.set('Could not load the document.')
    });
  }

  onChapterSelected(sel: { path: string; anchor: string }): void {
    if (sel.path === this.selectedPath()) {
      this.scrollToAnchor(sel.anchor);
    } else {
      this.pendingAnchor = sel.anchor;
      this.selectDoc(sel.path);
    }
  }

  refresh(): void {
    this.loadTree();
    const p = this.selectedPath();
    if (p) this.selectDoc(p);
  }

  /** Flatten all docs for the index view (no-README fallback). */
  indexEntries = computed(() => {
    const out: { label: string; path: string }[] = [];
    const walk = (node: DocsNode, prefix: string): void => {
      for (const child of node.children ?? []) {
        if (child.type === 'doc' && child.path) {
          out.push({ label: prefix ? `${prefix} / ${child.name}` : child.name, path: child.path });
        } else if (child.type === 'folder') {
          walk(child, prefix ? `${prefix} / ${child.name}` : child.name);
        }
      }
    };
    if (this.tree()) walk(this.tree()!, '');
    return out;
  });

  /**
   * Rewrite relative asset URLs so they resolve against the API.
   * src="..." relative → file endpoint; href="*.md" → left as-is (in-app nav);
   * other relative href → file endpoint; absolute / http(s) / data: / # / mailto: unchanged.
   */
  rewriteAssetUrls(md: string, docPath: string): string {
    const dir = docPath.includes('/') ? docPath.slice(0, docPath.lastIndexOf('/')) : '';
    return md.replace(/(src|href)="([^"]+)"/g, (match, attr: string, url: string) => {
      if (/^(https?:|data:|#|mailto:)/i.test(url) || url.startsWith('/')) return match;
      if (attr === 'href' && url.toLowerCase().endsWith('.md')) return match;
      const resolved = dir ? `${dir}/${url}` : url;
      return `${attr}="${this.docs.fileUrl(resolved)}"`;
    });
  }

  /** In-app navigation for relative .md links inside the rendered content. */
  onContentClick(event: MouseEvent): void {
    const link = (event.target as HTMLElement).closest('a');
    if (!link) return;
    const href = link.getAttribute('href') ?? '';
    if (!href.toLowerCase().endsWith('.md') || href.startsWith('/') || /^(https?:|data:)/i.test(href)) return;
    event.preventDefault();
    const docPath = this.selectedPath();
    if (!docPath) return;
    const dir = docPath.includes('/') ? docPath.slice(0, docPath.lastIndexOf('/')) : '';
    this.selectDoc(dir ? `${dir}/${href}` : href);
  }

  /** Public for tests. Sets ids on rendered H2s (same algorithm as the backend) so chapter anchors work. */
  markHeadings(): void {
    const container = this.el.nativeElement.querySelector('.doc-content') as HTMLElement | null;
    if (!container) return;
    const seen = new Map<string, number>();
    container.querySelectorAll('h2').forEach((h2) => {
      const base = slugify((h2.textContent ?? '').trim());
      const n = (seen.get(base) ?? 0) + 1;
      seen.set(base, n);
      h2.id = n === 1 ? base : `${base}-${n}`;
    });
    if (this.pendingAnchor) {
      this.scrollToAnchor(this.pendingAnchor);
      this.pendingAnchor = null;
    } else {
      (this.el.nativeElement.querySelector('.doc-content-pane') as HTMLElement | null)?.scrollTo?.(0, 0);
    }
  }

  private scrollToAnchor(anchor: string): void {
    this.el.nativeElement
      .querySelector(`.doc-content h2#${CSS.escape(anchor)}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
