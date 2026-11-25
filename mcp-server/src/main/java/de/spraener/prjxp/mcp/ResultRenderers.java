package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Shared text rendering for the prjxp tool results.
 * <p>
 * Both the MCP path ({@link PrjxpMcpTool}) and the hub chat path
 * ({@link de.spraener.prjxp.mcp.tools.PrjxpToolRegistration}) render the same records with these
 * methods, so the two surfaces show the model identical text. Each caller applies its own size
 * cap to the rendered text afterwards (the MCP path via {@link ToolResultCaps}, the hub path via
 * its {@code truncate}).
 */
public final class ResultRenderers {

    private ResultRenderers() {
    }

    /** One line per path, with a note when the list was cut by the limit. */
    public static String renderFileList(FileListResult result) {
        if (result == null || result.files().isEmpty()) {
            return "No files found.";
        }
        long directories = result.files().stream().filter(f -> f.endsWith("/")).count();
        String noun = directories == 0
                ? "file(s)"
                : (directories == result.files().size()
                        ? (directories == 1 ? "directory" : "directories")
                        : "entries");
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(result.files().size()).append(' ').append(noun);
        if (result.truncated()) {
            sb.append(" (truncated — narrow the pattern, list one level ('dir/'), or raise the limit)");
        }
        sb.append(":\n");
        for (String file : result.files()) {
            sb.append("  - ").append(file).append("\n");
        }
        return sb.toString();
    }
    /**
     * Header with the full per-direction totals (always visible, before the edge list that may be
     * capped): lets the model tell the page is partial and page the rest via offset/maxEdges.
     */
    public static String renderGraph(GraphView view) {
        if (view == null) {
            return "No graph view returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        if (view.candidates() != null && !view.candidates().isEmpty()) {
            StringBuilder sb = new StringBuilder("Ambiguous. Candidates:\n");
            for (String cand : view.candidates()) {
                sb.append("  - ").append(cand).append("\n");
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(view.project()).append("\n");
        if (view.file() != null) {
            sb.append("File: ").append(view.file()).append("\n");
        } else {
            sb.append("Node: ").append(view.nodeId()).append("\n");
        }
        sb.append("  ").append(view.nodeId());
        if (view.clusterId() != null || view.weight() != null) {
            sb.append("   [cluster:").append(view.clusterId() == null ? "?" : view.clusterId())
                    .append(", weight:").append(view.weight() == null ? "?" : String.format(Locale.ROOT, "%.2f", view.weight()))
                    .append("]");
        }
        // Efficiency measure 5: the AGGREGATED view carries module groups instead of raw edges.
        boolean aggregated = view.outgoingByModule() != null || view.incomingByModule() != null;
        if (view.totalOutgoing() != null || view.totalIncoming() != null) {
            sb.append("   [out:").append(view.totalOutgoing() == null ? "?" : view.totalOutgoing())
                    .append(", in:").append(view.totalIncoming() == null ? "?" : view.totalIncoming()).append("]");
            // The aggregation covers all edges by construction — no truncation hint in that mode.
            if (!aggregated
                    && ((view.totalOutgoing() != null && view.outgoing().size() < view.totalOutgoing())
                    || (view.totalIncoming() != null && view.incoming().size() < view.totalIncoming()))) {
                sb.append("  (page truncated — raise maxEdges or use offset)");
            }
        }
        sb.append("\n");
        if (aggregated) {
            sb.append("Aggregated by module:\n");
            if (view.outgoingByModule() != null) {
                sb.append("  outgoing (").append(view.totalOutgoing() == null ? "?" : view.totalOutgoing())
                        .append(" edges, ").append(view.outgoingByModule().size()).append(" modules):\n");
                for (GraphAggregate a : view.outgoingByModule()) {
                    renderAggregate(sb, a);
                }
            }
            if (view.incomingByModule() != null) {
                sb.append("  incoming (").append(view.totalIncoming() == null ? "?" : view.totalIncoming())
                        .append(" edges, ").append(view.incomingByModule().size()).append(" modules):\n");
                for (GraphAggregate a : view.incomingByModule()) {
                    renderAggregate(sb, a);
                }
            }
        } else {
            for (GraphNeighbor n : view.outgoing()) {
                sb.append("    ").append(n.type()).append(" -> ").append(n.neighborId())
                        .append(n.neighborFile() != null ? "  [" + n.neighborFile() + "]" : "")
                        .append("\n");
            }
            for (GraphNeighbor n : view.incoming()) {
                sb.append("    <- ").append(n.type()).append("  ").append(n.neighborId())
                        .append(n.neighborFile() != null ? "  [" + n.neighborFile() + "]" : "")
                        .append("\n");
            }
            if (view.outgoing().isEmpty() && view.incoming().isEmpty()) {
                sb.append("    (no edges)\n");
            }
        }
        return sb.toString();
    }

    /**
     * Renders a {@code graphSearch} result: seeds first, then the BFS/DFS nodes and the
     * typed edges, capped to the per-call token budget.
     */
    public static String renderGraphSearch(GraphSearchView view, int tokenBudget) {
        if (view == null) {
            return "No graph search view returned.";
        }
        if (view.error() != null) {
            return "ERROR: " + view.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Graph: ").append(view.project())
                .append(" | Seeds: ").append(String.join(", ", view.seeds()))
                .append(" | ").append(view.totalNodes()).append(" nodes, ")
                .append(view.totalEdges()).append(" edges\n");
        sb.append("NODES:\n");
        for (GraphSearchView.GraphSearchNode node : view.nodes()) {
            sb.append("  ").append(node.id());
            if (node.clusterId() != null) {
                sb.append("  [").append(node.clusterId()).append("]");
            }
            if (node.file() != null) {
                sb.append("  (").append(node.file()).append(")");
            }
            sb.append("\n");
        }
        sb.append("EDGES:\n");
        for (GraphSearchView.GraphSearchEdge edge : view.edges()) {
            sb.append("  ").append(edge.source()).append(" -[").append(edge.type()).append("]-> ")
                    .append(edge.target()).append("\n");
        }
        if (view.truncated()) {
            sb.append("[gekürzt: ").append(view.totalNodes()).append(" Knoten / ")
                    .append(view.totalEdges())
                    .append(" Kanten — tiefer mit depth/contextFilter oder höherem tokenBudget]\n");
        }
        return ToolResultCaps.capTokens(sb.toString(), tokenBudget);
    }

    /** Renders the {@code listConfig} catalog as one compact line per property. */
    public static String renderConfigCatalog(ConfigCatalog catalog, int tokenBudget) {
        if (catalog == null) {
            return "No config catalog returned.";
        }
        if (catalog.error() != null) {
            return "ERROR: " + catalog.error();
        }
        if (catalog.properties().isEmpty()) {
            return "No configuration properties found for project '" + catalog.project() + "'.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Config: ").append(catalog.project())
                .append(" (").append(catalog.properties().size()).append(" properties)\n");
        for (ConfigProperty property : catalog.properties()) {
            sb.append("  ").append(property.name());
            if (property.type() != null && !property.type().isBlank()) {
                sb.append(" (").append(property.type()).append(")");
            }
            if (property.defaultValue() != null && !property.defaultValue().isBlank()) {
                sb.append(" default=").append(property.defaultValue());
            }
            if (property.description() != null && !property.description().isBlank()) {
                sb.append(" — ").append(property.description());
            }
            if (property.source() != null) {
                sb.append("  [").append(property.source()).append("]");
            }
            sb.append("\n");
        }
        return ToolResultCaps.capTokens(sb.toString(), tokenBudget);
    }

    /** One module group of the AGGREGATED view: counts, edge-type breakdown, and top classes. */
    private static void renderAggregate(StringBuilder sb, GraphAggregate a) {
        sb.append("    ").append(a.module()).append(": ").append(a.edgeCount()).append(" edges, ")
                .append(a.classCount()).append(" classes");
        if (!a.typeCounts().isEmpty()) {
            sb.append(" [").append(a.typeCounts().entrySet().stream()
                    .map(en -> en.getKey() + " " + en.getValue())
                    .collect(Collectors.joining(", "))).append("]");
        }
        if (!a.topClasses().isEmpty()) {
            sb.append(" — ").append(a.topClasses().stream()
                    .map(c -> c.className() + "(" + c.count() + ")")
                    .collect(Collectors.joining(", ")));
        }
        sb.append("\n");
    }
}
