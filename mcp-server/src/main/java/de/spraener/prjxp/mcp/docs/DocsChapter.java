package de.spraener.prjxp.mcp.docs;

/** An H2 chapter of a document. anchor = slug, deduplicated within the document. */
public record DocsChapter(String title, String anchor) {
}
