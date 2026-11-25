import { Component, Output, EventEmitter, input, signal } from '@angular/core';
import { DocsNode } from './docs.model';

@Component({
  selector: 'doc-tree',
  standalone: true,
  imports: [DocTreeComponent], // recursive
  templateUrl: './doc-tree.component.html',
  styleUrl: './doc-tree.component.css'
})
export class DocTreeComponent {
  node = input<DocsNode>();
  selectedPath = input<string | null>(null);
  @Output() docSelected = new EventEmitter<string>();
  @Output() chapterSelected = new EventEmitter<{ path: string; anchor: string }>();

  private expanded = signal(new Set<string>()); // folder names

  isExpanded(node: DocsNode): boolean {
    return this.expanded().has(node.name);
  }

  toggle(node: DocsNode): void {
    const next = new Set(this.expanded());
    if (next.has(node.name)) {
      next.delete(node.name);
    } else {
      next.add(node.name);
    }
    this.expanded.set(next);
  }
}
