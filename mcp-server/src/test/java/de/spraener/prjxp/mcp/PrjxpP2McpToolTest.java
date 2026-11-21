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
    void exposesTheWholeSurfaceIncludingProjectDiscovery() {
        List<String> names = Arrays.stream(PrjxpP2McpTool.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "listProjects", "vectorSearch", "grep", "readFile", "readBySignature",
                "listFiles", "graphFile", "graphNode", "graphSearch", "graphCluster",
                "graphClusterOverview", "semanticGraphNode", "semanticGraphCluster",
                "semanticGraphOverview", "listConfig", "readChunks");
    }

    @Test
    void passesTheProjectThroughForTheGraphTools() {
        tool.graphFile("a/B.java", "legacy", 5, true, 2, "FULL", 800);
        tool.graphCluster("c6", "prjxp", 7);
        tool.graphClusterOverview("prjxp", 4);
        tool.semanticGraphCluster("s2", "legacy", 3);
        tool.semanticGraphNode("n1", "prjxp", 9);
        tool.semanticGraphOverview("legacy", 6);

        verify(service).graphFile("a/B.java", "legacy", 5, true, 2, "FULL", 800);
        verify(service).graphCluster("c6", "prjxp", 7);
        verify(service).graphClusterOverview("prjxp", 4);
        verify(service).semanticGraphCluster("s2", "legacy", 3);
        verify(service).semanticGraphNode("n1", "prjxp", 9);
        verify(service).semanticGraphOverview("legacy", 6);
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
            if (method.getName().equals("listProjects")) {
                continue; // discovery tool: it answers the question which projects exist
            }
            assertThat(method.getParameters())
                    .as(method.getName())
                    .anyMatch(p -> p.getName().equals("project"));
        }
    }

    @Test
    void listProjectsDelegatesToTheService() {
        assertThat(tool.listProjects()).isEmpty();

        verify(service).listProjects();
    }
}
