package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;

import java.io.File;
import java.util.Optional;

/**
 * Builds a code knowledge graph from source files.
 *
 * <p>Two-phase, mirroring the chunker's pre-walk/post-walk split:
 * <ul>
 *   <li>{@link #beginRun()} — called once at run start; clears accumulated state.</li>
 *   <li>{@link #process(File)} — called per matching file during the pre-walk; parses the
 *       AST and accumulates nodes and edges.</li>
 *   <li>{@link #build()} — called after the walk; finalizes and returns the graph
 *       (unrated — ratings are applied by a {@link GraphWeighter}).</li>
 * </ul>
 *
 * <p>Optional capability: a language pack that cannot build a graph returns
 * {@link java.util.Optional#empty()} from {@link LanguagePack#codeGraph()}.
 */
public interface CodeGraphBuilder {

    /** Whether this builder handles the given file (language dispatch). */
    boolean matches(File f);

    /** Clears accumulated state for a new run. */
    void beginRun();

    /** Parses the file's AST and accumulates nodes/edges. */
    void process(File f);

    /** Finalizes and returns the accumulated graph (unrated). */
    CodeGraph build();

    /**
     * The built and weighted graph after the pre-walk, or empty before {@link #build()}
     * has completed / when no weighting ran. Lets the main walk attach ratings to chunks.
     */
    default Optional<CodeGraph> weightedGraph() { return Optional.empty(); }
}
