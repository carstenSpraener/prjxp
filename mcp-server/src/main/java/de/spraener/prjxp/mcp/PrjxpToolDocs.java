package de.spraener.prjxp.mcp;

/**
 * Single source of truth for the prjxp tool descriptions.
 * <p>
 * Both the MCP path ({@link PrjxpMcpTool} via {@code @McpTool}) and the hub chat path
 * ({@link de.spraener.prjxp.mcp.tools.PrjxpToolRegistration}) read their tool-level text from here,
 * so the two surfaces can never drift apart.
 * <p>
 * The texts are deliberately tight: they are sent to the model on every request, so the whole set
 * is budgeted (see {@code PrjxpToolDocsTest}).
 */
public final class PrjxpToolDocs {

    private PrjxpToolDocs() {
    }

    public static final String VECTOR_SEARCH = """
            Start here for where/how questions. Pass a short English keyword query
            (e.g. "PxChunk definition usage"), identifiers/class names/file names verbatim
            — not the raw German question. Returns pointers only (file, chunk_id, score) — no content.
            Read content with readBySignature(FQN#name) or readFile(offset/limit).
            Defaults: sourceType=code, maxResults=10; sourceType=all for prose.
            tokenBudget default 2000; raise only for a deliberate full view.
            """;

    public static final String GREP = """
            Exact text search. Returns only the file list "count  path" (like grep -l) — no content.
            Read content explicitly with readBySignature(FQN#name) or readFile(offset/limit).
            Exact token, not substring: "ChunkRanking" does NOT match "ChunkRankingService".
            Use listFiles("*ChunkRanking*") for file names, or the full symbol name. Tests default true; max 100 files.
            """;

    public static final String READ_FILE = """
            Reads a file. Prefer readBySignature(FQN#name) for one symbol.
            Default: 100 lines from offset. full=true only for files ≤200 lines (hard cap 200 lines / 20k chars);
            for larger files use offset/limit or readBySignature.
            For configuration questions use listConfig(project).
            """;

    public static final String READ_BY_SIGNATURE = """
            Reads a symbol in any indexed language. Use 'FQN#name' (or name + container) for javadoc and method body.
            A class FQN returns its class frame with signatures only.
            For config classes prefer the class FQN — it returns the class frame with fields.
            """;

    public static final String LIST_PROJECTS = """
            Lists searchable projects and last index update. Call before search tools to learn valid project values.
            """;

    public static final String LIST_FILES = """
            Lists which files exist in the index (structure, not content). 'path' is a prefix or glob ('*' spans '/'); empty = all.
            """;

    public static final String GRAPH_FILE = """
            Typed code-graph neighbours of a file (imports, inheritance, calls, and related edges). includeExternal adds library edges.
            maxEdges max 500; offset pages; totals = full count. tokenBudget default 2000; raise only for a deliberate full view.
            """;

    public static final String GRAPH_NODE = """
            Typed neighbours of a class or method id — includes CONTAINS (class -> its methods). For call sites, grep(symbol) is cheaper.
            includeExternal adds library edges. maxEdges max 500; offset pages; totals = full count.
            tokenBudget default 2000; raise only for a deliberate full view.
            """;

    public static final String GRAPH_SEARCH = """
            Search the code graph by keyword and return a bounded subgraph (seeds + BFS/DFS
            neighbourhood). Use for "what is X" / "where is X used" / "how is X wired" —
            not for reading content. Pass a short English keyword query; keep identifiers,
            class names and file names verbatim. Defaults: mode=bfs, depth=3,
            tokenBudget=2000, maxSeeds=3. Use graphNode for one exact symbol, graphFile for
            one file, vectorSearch for prose/semantic discovery.
            """;

    public static final String GRAPH_CLUSTER = """
            Important typed-code cluster members ranked by degree. Use with a cluster id from vectorSearch or graphNode.
            """;

    public static final String GRAPH_CLUSTER_OVERVIEW = """
            Largest typed-code clusters with sizes and top members. Use to explore architecture without a starting class.
            """;

    public static final String SEMANTIC_GRAPH_CLUSTER = """
            Semantic cluster members across docs and class frames. Not the typed code graph (graphCluster).
            """;

    public static final String SEMANTIC_GRAPH_NODE = """
            Project-scoped semantic neighbours with files and scores. Not typed code relationships (graphNode).
            """;

    public static final String SEMANTIC_GRAPH_OVERVIEW = """
            Largest semantic clusters with member counts, top files, model, and calibration warning. Not graphClusterOverview.
            """;

    public static final String READ_CHUNKS = """
            Read the full content of one or more chunks by their chunk_id (from vectorSearch neighbours or chunk_id fields). \
            Returns the recombined content of each chunk; prefer 1–3 ids, each chunk is re-sent on every later turn. \
            Parameters: project (required), ids (comma-separated chunk_ids, max 10).""";

    public static final String LIST_CONFIG = """
            List configuration properties (name, type, default, description, source).
            Use for "which config parameters exist?" — do not read every config class.
            """;
}
