package de.spraener.prjxp.mcp;

/**
 * Single source of truth for the fourteen prjxp tool descriptions.
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
            Start here for where/how questions. Follow POINTERS to readFile, readBySignature(FQN#name), or graphNode.
            Use grep("@Annotation", POINTERS) for implementations and grep(symbol, POINTERS) for call sites.
            Defaults: POINTERS, sourceType=code, maxResults=10; use sourceType=all for prose. contentLevel=NAMES = filenames only.
            """;

    public static final String GREP = """
            Exact text search. Default NAMES = grep -l: one line per file "count  path", sorted by hit count — the cheap "where is X" answer.
            FULL returns bodies; SKELETON signatures; POINTERS enumerates. Use readBySignature(FQN#name) for one method. Tests default true; max 100 files.
            """;

    public static final String READ_FILE = """
            Reads a complete file from the index. Path lookup is exact -> suffix -> basename; offset/limit page large files.
            """;

    public static final String READ_BY_SIGNATURE = """
            Reads a symbol in any indexed language. Use 'FQN#name' (or name + container) for javadoc and method body.
            A class FQN returns its class frame with signatures only.
            """;

    public static final String LIST_PROJECTS = """
            Lists searchable projects and last index update. Call before search tools to learn valid project values.
            """;

    public static final String LIST_FILES = """
            Lists which files exist in the index (structure, not content). 'path' is a prefix or glob ('*' spans '/'); empty = all.
            """;

    public static final String GRAPH_FILE = """
            Typed code-graph neighbours of a file (imports, inheritance, calls, and related edges). includeExternal adds library edges.
            maxEdges max 500; offset pages; totals = full count.
            """;

    public static final String GRAPH_NODE = """
            Typed neighbours of a class or method id. For call sites, grep(symbol, POINTERS) is cheaper. includeExternal adds library edges.
            maxEdges max 500; offset pages; totals = full count.
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
            Returns the recombined content of each chunk. Use this to explore neighbours listed in a vectorSearch result. \
            Parameters: project (required), ids (comma-separated chunk_ids, max 10).""";
}
