package de.spraener.prjxp.mcp;

/**
 * Single source of truth for the ten prjxp tool descriptions.
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
            Semantic entry point. Use it first for "where is X", "how does Y work", "which classes are involved".

            FLOWS
            - discover: vectorSearch -> readFile(path) for a whole file, readBySignature(fqn) for one method.
            - enumerate implementations: grep("@Annotation", contentLevel=POINTERS) - implementations discovered at
              runtime have no edge in the code graph, so vectorSearch cannot find them.
            - call sites: grep("symbolName", contentLevel=POINTERS) - cheaper than graphNode.
            - architecture: vectorSearch + graphNode / graphClusterOverview.

            RETURNS per contentLevel: POINTERS (default) emits file path plus readFile/readBySignature/graphNode hints
            per class, roughly 50 bytes each - follow them. SKELETON emits class frames with signatures. FULL emits
            the bodies of the hits.

            Defaults are the cheap path: contentLevel=POINTERS, sourceType=code, maxResults=10.
            """;

    public static final String GREP = """
            Exact full-text search. Jobs: exact signature -> FULL body; annotation -> enumerate implementations; symbol -> call sites.
            contentLevel=POINTERS enumerates (cheap); FULL (default) fetches a body. includeTests defaults true; limit = hits after grouping, max 100.
            """;

    public static final String READ_FILE = """
            Complete source view of one file, reconstructed from the index. Use after vectorSearch/grep.
            'file' is lenient (exact -> suffix -> basename); offset/limit page large files.
            """;

    public static final String READ_BY_SIGNATURE = """
            Complete implementation of a symbol (javadoc + body) by name, any indexed language.
            'method' is a name or FQN; class FQNs return the class frame. container disambiguates a bare name.
            """;

    public static final String LIST_PROJECTS = """
            Lists searchable projects with their last index update. Call before the search tools to learn valid 'project' values.
            """;

    public static final String LIST_FILES = """
            Lists which files exist in the index (structure, not content). 'path' is a prefix or glob ('*' spans '/'); empty = all.
            """;

    public static final String GRAPH_FILE = """
            Typed code-graph neighbours of one file (import/extends/implements/field/param/return/call edges).
            includeExternal reveals JDK/library edges (hidden by default).
            """;

    public static final String GRAPH_NODE = """
            Typed code-graph neighbours of one node id (FQN or FQN#name(params)). Expensive for call sites - grep(symbol, POINTERS) is cheaper.
            includeExternal reveals JDK/library edges (hidden by default).
            """;

    public static final String GRAPH_CLUSTER = """
            Most important members of one cluster, ranked by degree centrality. Use after a cluster id is known (from vectorSearch [cluster:cN] or graphNode).
            """;

    public static final String GRAPH_CLUSTER_OVERVIEW = """
            Map of the project's clusters - the N largest with size and top members. Use to explore the architecture without a starting class; then graphCluster.
            """;
}
