export interface DocsChapter {
  title: string;
  anchor: string;
}

export interface DocsNode {
  name: string;
  type: 'root' | 'folder' | 'doc';
  /** relative to the docs root, forward slashes — docs only */
  path?: string;
  /** H2 chapters in document order — docs only */
  chapters?: DocsChapter[];
  /** folders first, then docs — root/folder only */
  children?: DocsNode[];
}

/** Must match the backend slug algorithm exactly. */
export function slugify(text: string): string {
  const s = text.toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-');
  return s.length > 0 ? s : 'section';
}
