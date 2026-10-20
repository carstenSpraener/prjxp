package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Test fixture: a COPY of the host's LanguagePack interface (same FQN,
 * different class file — note the marker method). Simulates a plugin JAR
 * that bundles the host API. With child-first delegation this copy is
 * loaded by the plugin class loader, breaking class identity.
 */
public interface LanguagePack {

    String language();

    String mimeType();

    default Stream<PxChunker> findPxChunkers(File f) {
        return Stream.empty();
    }

    default Stream<PxChunker> listPostWalkChunkers() {
        return Stream.empty();
    }

    default Optional<GoldenRetriever> retriever() {
        return Optional.empty();
    }

    /** Marker: makes this a different class than the host's LanguagePack. */
    int identityBreakMarker();
}
