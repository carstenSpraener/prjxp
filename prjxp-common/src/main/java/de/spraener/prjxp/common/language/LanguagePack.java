package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A LanguagePack bundles everything prjxp needs to support one programming language:
 * a chunking side (files -> PxChunks) and a retrieval side (a GoldenRetriever).
 *
 * Built-in packs are Spring beans inside the engine modules (chunk-norris / golden-retriever).
 * External packs are plain classes registered via
 * META-INF/services/de.spraener.prjxp.common.language.LanguagePack and only need to
 * depend on prjxp-common.
 */
public interface LanguagePack {

    /** Unique language identifier, e.g. "java", "typescript", "visualbasic". */
    String language();

    /** MIME type carried by this pack's chunks, e.g. "text/x-java-code". */
    String mimeType();

    /** Chunker side: chunkers that can process the given file (extension and/or content). */
    default Stream<PxChunker> findPxChunkers(File f) {
        return Stream.empty();
    }

    /** Post-walk chunkers: run once after the file walk (e.g. JavaDependenciesChunker). */
    default Stream<PxChunker> listPostWalkChunkers() {
        return Stream.empty();
    }

    /** Retriever side: this pack's retriever, or empty if not provided. */
    default Optional<GoldenRetriever> retriever() {
        return Optional.empty();
    }
}
