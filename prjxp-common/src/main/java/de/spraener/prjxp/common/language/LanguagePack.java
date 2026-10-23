package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A LanguagePack bundles everything prjxp needs to support one programming language:
 * a chunking side (files -> PxChunks) and a retrieval side (a GoldenRetriever).
 *
 * <p>Built-in packs are Spring beans inside the engine modules (chunk-norris / golden-retriever).
 * External packs are provided by plugin JARs via {@link LanguagePackProvider} and loaded
 * into the {@link LanguagePackRegistry} at startup; a plugin only needs to depend on
 * prjxp-common.
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

    /**
     * Reader side: this pack's {@link FileViewProvider} for lossless {@code readFile}
     * rendering, or empty if not provided (the host falls back to the generic reader).
     *
     * <p>Built-in packs are Spring-managed and register their providers as plain
     * {@code FileViewProvider} beans, so they use the default no-op. External (plugin)
     * packs — which are instantiated no-arg inside an isolating class loader and cannot
     * be Spring beans — override this to expose their provider to the host's
     * {@code FileViewRegistry}.
     */
    default Optional<FileViewProvider> fileViewProvider() {
        return Optional.empty();
    }

    /**
     * Optional host binding: the host calls this once after loading the pack,
     * before first use, to hand the pack's retriever access to the embedding
     * store (the {@link PxChunkDaoProvider}).
     *
     * <p>Built-in packs are Spring-managed and already receive their dependencies
     * via constructor injection, so they use the default no-op. External (plugin)
     * packs — which are instantiated no-arg inside an isolating class loader and
     * cannot be Spring-injected — override this to create a store-aware retriever,
     * enabling full part reassembly and parent-frame enrichment in the host
     * retrieval path. Without a binding the retriever degrades gracefully to
     * best-effort (parts/parents only from the chunks handed in per request).
     */
    default void bind(PxChunkDaoProvider chunkDaoProvider) {
    }
}
