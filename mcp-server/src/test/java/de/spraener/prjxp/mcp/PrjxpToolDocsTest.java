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
                + PrjxpToolDocs.GRAPH_CLUSTER.length()
                + PrjxpToolDocs.GRAPH_CLUSTER_OVERVIEW.length();

        assertThat(total)
                .as("sum of the ten tool texts (was %d)", total)
                .isLessThan(2600);
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
        assertThat(total)
                .as("sum of all tool texts + parameter descriptions (was %d)", total)
                .isLessThan(4250);
    }
}
