package de.spraener.prjxp.mcp.docs;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A node in the documentation tree.
 * type: "root" | "folder" | "doc".
 * path: only for "doc" — relative to the docs root, forward slashes.
 * chapters: only for "doc" — H2 headings in document order.
 * children: only for "root"/"folder" — folders first, then docs, each sorted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocsNode(
        String name,
        String type,
        String path,
        List<DocsChapter> chapters,
        List<DocsNode> children
) {
    public static DocsNode root(String name, List<DocsNode> children) {
        return new DocsNode(name, "root", null, null, children);
    }

    public static DocsNode folder(String name, List<DocsNode> children) {
        return new DocsNode(name, "folder", null, null, children);
    }

    public static DocsNode doc(String name, String path, List<DocsChapter> chapters) {
        return new DocsNode(name, "doc", path, chapters, null);
    }
}
