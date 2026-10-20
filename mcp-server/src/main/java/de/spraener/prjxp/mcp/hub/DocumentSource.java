package de.spraener.prjxp.mcp.hub;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A source of documents to be chunked and embedded as an addition to a project's index.
 * Implementations return paths RELATIVE TO THE PROJECT ROOT; the embedding run resolves them
 * against the project's rootDir.
 */
public interface DocumentSource {

    /** Metadata key stamped on every chunk produced from a document source (drives the doc-scoped reset). */
    String DOCPIPE_GENERATED = "docpipe_generated";

    Stream<Path> documentStream();
}
