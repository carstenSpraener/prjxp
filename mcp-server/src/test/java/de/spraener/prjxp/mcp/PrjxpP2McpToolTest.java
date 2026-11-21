package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrjxpP2McpToolTest {

    private PrjxpToolService service;
    private PrjxpP2McpTool tool;

    @BeforeEach
    void setUp() {
        service = mock(PrjxpToolService.class);
        tool = new PrjxpP2McpTool(service, new P2Measures(true, true, true));
    }

    @Test
    void exposesExactlyTheNineUsedTools() {
        List<String> names = Arrays.stream(PrjxpP2McpTool.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "vectorSearch", "grep", "readFile", "readBySignature", "listFiles",
                "graphNode", "graphSearch", "listConfig", "readChunks");
        assertThat(names).doesNotContain("listProjects", "graphFile", "graphCluster",
                "graphClusterOverview", "semanticGraphNode", "semanticGraphCluster",
                "semanticGraphOverview");
    }

    @Test
    void passesTheProjectThroughForGrep() {
        tool.grep("run", "legacy", "regex", "java", 5, false);

        verify(service).grepP2("run", "legacy", "regex", "java", 5, false, true);
    }

    @Test
    void passesTheProjectThroughForVectorSearch() {
        tool.vectorSearch("chunking", "prjxp", 0.8, 3, "all", true, 1000);

        verify(service).vectorSearchP2("chunking", "prjxp", 0.8, 3, "all", true, 1000, true);
    }

    @Test
    void passesTheProjectThroughForReadBySignature() {
        tool.readBySignature("a.B", "prjxp", null, true, 2);

        verify(service).readBySignatureP2("a.B", null, "prjxp", true, 2, true);
    }

    @Test
    void measureSwitchesReachTheService() {
        PrjxpP2McpTool ablated = new PrjxpP2McpTool(service, new P2Measures(false, false, false));

        ablated.grep("run", "prjxp", null, null, null, null);
        ablated.vectorSearch("chunking", "prjxp", null, null, null, null, null);
        ablated.readBySignature("a.B", "prjxp", null, null, null);

        verify(service).grepP2("run", "prjxp", null, null, null, null, false);
        verify(service).vectorSearchP2("chunking", "prjxp", null, null, null, null, null, false);
        verify(service).readBySignatureP2("a.B", null, "prjxp", null, null, false);
    }

    @Test
    void everyToolTakesTheProjectSoOneHubServesManyProjects() {
        for (Method method : PrjxpP2McpTool.class.getDeclaredMethods()) {
            if (method.getAnnotation(McpTool.class) == null) {
                continue;
            }
            assertThat(method.getParameters())
                    .as(method.getName())
                    .anyMatch(p -> p.getName().equals("project"));
        }
    }
}
