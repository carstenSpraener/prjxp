package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.PxChunk;

import java.util.Locale;
import java.util.Map;

public final class SourceTypeFilter {
    public static final String ALL = "all";
    public static final String CODE = "code";
    public static final String DOCS = "docs";
    /** Prose-document mime types; everything else counts as code. */
    static final String DOC_MIMES = "text/markdown|application/pdf";

    private SourceTypeFilter() {
    }

    /** Mime filters for the vector search; empty map for {@code all} (and for unknown values). */
    public static Map<String, String> mimeFilters(String sourceType) {
        String normalized = sourceType == null ? ALL : sourceType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case CODE -> Map.of(PxChunk.PXCHUNK_MIME_TYPE, "!" + DOC_MIMES);
            case DOCS -> Map.of(PxChunk.PXCHUNK_MIME_TYPE, DOC_MIMES);
            default -> Map.of();
        };
    }
}
