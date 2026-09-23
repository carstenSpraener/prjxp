package de.spraener.prjxp.common.reader;

import de.spraener.prjxp.common.model.PxChunk;
import java.util.List;

public interface FileViewProvider {
    /** MIME type this provider renders, e.g. "text/x-java-code". */
    String mimeType();

    /** Human language name for reporting, e.g. "java". */
    String language();

    /**
     * Renders the source view of one file from its COMBINED units (parts already reassembled via PxChunk.combine).
     * Units may be in any order; the provider sorts as needed. Never returns null.
     */
    String render(List<PxChunk> units);
}
