package de.spraener.prjxp.mcp;

/**
 * Tool descriptions of the P2 contract (Benchmark-Befund 02, measures 2–5).
 * <p>
 * P2 is a decision guide, not a catalogue: each text names the one situation the tool is for and
 * the cheaper alternative.
 * The total length is budgeted by {@code PrjxpP2ToolDocsTest}; the schema is sent on every turn.
 */
public final class PrjxpP2ToolDocs {

    private PrjxpP2ToolDocs() {
    }

    public static final String LIST_PROJECTS = """
            Lists the searchable projects with their last index update. Call it once to learn the
            project name every other tool expects.
            """;

    public static final String VECTOR_SEARCH = """
            Where/how discovery. Short English keywords, identifiers verbatim. Returns up to 10 files with
            chunk_id, score and a short snippet. Read a hit with readChunks(ids=…) — prefer 1–3 ids.
            sourceType=code (default) | docs | all; tests only with includeTests=true.
            """;

    public static final String GREP = """
            Find a known text and get file:line plus the matching line. mode=substring (default) |
            exact (whole word) | regex. Use it for identifiers, error strings and call sites;
            use vectorSearch when you only know the concept. Tests default on; limit default 20 hits.
            """;

    public static final String READ_FILE = """
            Read a file range when you need several symbols or surrounding context. Default 100 lines
            from offset. Prefer readBySignature for one symbol. full=true only for files ≤200 lines.
            """;

    public static final String READ_BY_SIGNATURE = """
            Read one symbol. 'FQN#name' or name+container returns javadoc and body. A class FQN returns
            the frame plus the first method bodies (includeBodies=false to suppress, maxBodies default 3).
            """;

    public static final String LIST_FILES = """
            List existing files by prefix or glob ('*' spans '/'). Use to locate a path, not to read content.
            """;

    public static final String GRAPH_FILE = """
            Typed neighbours of one file (imports, inheritance, calls). Use it to see what a file
            pulls in or what depends on it; offset pages, totals = full count.
            """;

    public static final String GRAPH_NODE = """
            Typed neighbours of one class/method id (CALL, CONTAINS, FIELD, …). Use for wiring and
            inheritance; use grep for call sites and readBySignature for behaviour.
            """;

    public static final String GRAPH_SEARCH = """
            Bounded subgraph for "what is X wired to". Keyword query, mode=bfs|dfs, depth 1–6.
            Use graphNode for one exact symbol, vectorSearch for concept discovery.
            """;

    public static final String GRAPH_CLUSTER = """
            Important typed-code cluster members ranked by degree. The id comes from vectorSearch or
            graphNode; use graphClusterOverview when you have no starting class.
            """;

    public static final String GRAPH_CLUSTER_OVERVIEW = """
            Largest typed-code clusters with sizes and top members. Use to explore architecture while
            you have no starting class. Not the semantic clusters (semanticGraphOverview).
            """;

    public static final String SEMANTIC_GRAPH_CLUSTER = """
            Semantic cluster members across docs and class frames. Not typed code relationships
            (graphCluster).
            """;

    public static final String SEMANTIC_GRAPH_NODE = """
            Project-scoped semantic neighbours with files and scores. Not typed code relationships
            (graphNode).
            """;

    public static final String SEMANTIC_GRAPH_OVERVIEW = """
            Largest semantic clusters with member counts, top files and calibration warning. Not
            graphClusterOverview.
            """;

    public static final String LIST_CONFIG = """
            Configured properties (name, type, default, source). Use for "which setting controls X"
            instead of reading config classes.
            """;

    public static final String READ_CHUNKS = """
            Full content of chunks by id from vectorSearch. Comma-separated ids, max 10; prefer 1–3.
            """;
}
