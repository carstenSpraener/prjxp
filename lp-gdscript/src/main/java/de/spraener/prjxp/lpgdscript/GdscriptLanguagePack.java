package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Language pack for GDScript: wires the {@link GdscriptCodeChunker},
 * {@link GdscriptRetriever} and {@link GdscriptFileViewProvider}.
 * {@link #bind(PxChunkDaoProvider)} re-creates the retriever with the host's
 * dao provider so it can enrich hits (parent header + func doc).
 */
public class GdscriptLanguagePack implements LanguagePack {

    public static final String LANGUAGE = "gdscript";

    private final GdscriptCodeChunker chunker = new GdscriptCodeChunker();
    private volatile GdscriptRetriever retriever = new GdscriptRetriever();
    private final GdscriptFileViewProvider fileViewProvider = new GdscriptFileViewProvider();

    @Override
    public String language() {
        return LANGUAGE;
    }

    @Override
    public String mimeType() {
        return GdscriptCodeChunker.GDSCRIPT_CODE_MIME_TYPE;
    }

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return chunker.matches(f) ? Stream.of(chunker) : Stream.empty();
    }

    @Override
    public Optional<GoldenRetriever> retriever() {
        return Optional.of(retriever);
    }

    @Override
    public Optional<FileViewProvider> fileViewProvider() {
        return Optional.of(fileViewProvider);
    }

    @Override
    public void bind(PxChunkDaoProvider chunkDaoProvider) {
        this.retriever = new GdscriptRetriever(chunkDaoProvider);
    }
}
