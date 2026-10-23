package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The C# Language Pack: bundles the chunking side ({@link CSharpCodeChunker}) and
 * the retrieval side ({@link CSharpRetriever}) for the {@code csharp} language.
 *
 * <p>Plain, non-Spring object — it is instantiated by {@link CSharpLanguagePackProvider}
 * inside the host's isolating plugin class loader. The retriever starts out
 * self-contained (no store access); the host then calls {@link #bind} to hand it
 * the {@link PxChunkDaoProvider}, which enables full part reassembly and
 * parent-frame enrichment in the host retrieval path.
 */
public class CSharpLanguagePack implements LanguagePack {

    public static final String LANGUAGE = "csharp";

    private final CSharpCodeChunker chunker = new CSharpCodeChunker();
    /** Replaced by {@link #bind} once the host provides store access. */
    private volatile CSharpRetriever retriever = new CSharpRetriever();
    /** Reader side: splices member bodies into the type frames for lossless {@code readFile}. */
    private final CSharpFileViewProvider fileViewProvider = new CSharpFileViewProvider();

    @Override
    public String language() {
        return LANGUAGE;
    }

    @Override
    public String mimeType() {
        return CSharpCodeChunker.CSHARP_CODE_MIME_TYPE;
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

    /**
     * Host binding: recreates the retriever with store access so it can fetch
     * missing parts and parent frames from the embedding store. Called once by
     * the host after loading; a {@code null} provider keeps the self-contained
     * (best-effort) retriever.
     */
    @Override
    public void bind(PxChunkDaoProvider chunkDaoProvider) {
        this.retriever = new CSharpRetriever(chunkDaoProvider);
    }
}
