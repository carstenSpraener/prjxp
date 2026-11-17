package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the token budget of the tool surface: the descriptions are sent to the model on every
 * request, so the whole set is capped.
 */
class PrjxpToolDocsTest {

    @Test
    void toolDescriptionsStayWithinBudget() {
        int total = PrjxpToolDocs.VECTOR_SEARCH.length()
                + PrjxpToolDocs.GREP.length()
                + PrjxpToolDocs.READ_FILE.length()
                + PrjxpToolDocs.READ_BY_SIGNATURE.length()
                + PrjxpToolDocs.LIST_PROJECTS.length()
                + PrjxpToolDocs.LIST_FILES.length()
                + PrjxpToolDocs.GRAPH_FILE.length()
                + PrjxpToolDocs.GRAPH_NODE.length()
                + PrjxpToolDocs.GRAPH_SEARCH.length()
                + PrjxpToolDocs.GRAPH_CLUSTER.length()
                + PrjxpToolDocs.GRAPH_CLUSTER_OVERVIEW.length()
                + PrjxpToolDocs.SEMANTIC_GRAPH_CLUSTER.length()
                + PrjxpToolDocs.SEMANTIC_GRAPH_NODE.length()
                + PrjxpToolDocs.SEMANTIC_GRAPH_OVERVIEW.length()
                + PrjxpToolDocs.READ_CHUNKS.length()
                + PrjxpToolDocs.LIST_CONFIG.length();

        // Raised 2600 -> 3600: graphSearch (one call replaces the 3-4 call F3/F5 detour)
        // and listConfig (one call replaces eight config-file reads) are new entry points.
        assertThat(total)
                .as("sum of the sixteen tool texts (was %d)", total)
                .isLessThan(3600);
    }

    @Test
    void readBySignatureDocumentsTheMethodBodyForm() {
        // Regression guard: a class FQN returns only the class frame, so the descriptions must
        // steer the model to the 'FQN#name' form for method bodies (and away from grep FULL).
        assertThat(PrjxpToolDocs.READ_BY_SIGNATURE)
                .contains("'FQN#name'")
                .contains("class frame");
        assertThat(PrjxpToolDocs.VECTOR_SEARCH)
                .contains("readBySignature(FQN#name)");
        assertThat(PrjxpToolDocs.GREP)
                .contains("readBySignature(FQN#name)");
    }

    @Test
    void searchToolsDocumentPointersOnlyWithoutContent() {
        // the search tools no longer offer a contentLevel knob: the docs must promise
        // pointers/file list only and steer content reads to readBySignature/readFile
        assertThat(PrjxpToolDocs.VECTOR_SEARCH)
                .contains("pointers only")
                .contains("no content");
        assertThat(PrjxpToolDocs.GREP)
                .contains("grep -l")
                .contains("no content");
        assertThat(PrjxpToolDocs.READ_FILE)
                .contains("readBySignature(FQN#name)")
                .contains("offset/limit")
                .contains("full=true");
        assertThat(PrjxpToolDocs.READ_CHUNKS)
                .contains("1–3");
    }

    @Test
    void toolAndParameterDescriptionsStayWithinBudget() {
        Retention retention = McpToolParam.class.getAnnotation(Retention.class);
        if (retention == null || retention.value() != RetentionPolicy.RUNTIME) {
            // @McpToolParam is not visible at runtime, so the per-request text cannot be measured via reflection.
            return;
        }

        int total = 0;
        for (Method method : PrjxpMcpTool.class.getDeclaredMethods()) {
            McpTool tool = method.getAnnotation(McpTool.class);
            if (tool == null) {
                continue;
            }
            total += tool.description().length();
            for (Parameter parameter : method.getParameters()) {
                McpToolParam param = parameter.getAnnotation(McpToolParam.class);
                if (param != null) {
                    total += param.description().length();
                }
            }
        }

        // Raised 4000 -> 4250: the readBySignature/grep texts now steer the model to the
        // 'FQN#name' method-body form (cheaper than grep FULL), which costs ~200 chars per request.
        // Raised 4250 -> 4500: the NAMES (filenames-only) mode and graphNode pagination
        // (maxEdges 500 + offset + totals) are new capabilities the model must know about.
        // The one-time per-request cost (~60 tokens) is dwarfed by the per-call savings of NAMES.
        // Raised 4500 -> 4600: POINTERS now renders one compact pointer block per file (no content);
        // the descriptions say so and steer readChunks to few ids, saving far more per call.
        // Raised 4600 -> 6000: graphSearch + listConfig + the tokenBudget parameter on the
        // search/graph tools are new capability surface that saves multiple calls per question.
        assertThat(total)
                .as("sum of all tool texts + parameter descriptions (was %d)", total)
                .isLessThan(6000);
    }
}
