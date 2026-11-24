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

class PrjxpMcpToolTest {

    private PrjxpToolService service;
    private PrjxpMcpTool tool;

    @BeforeEach
    void setUp() {
        service = mock(PrjxpToolService.class);
        tool = new PrjxpMcpTool(service);
    }

    @Test
    void exposesTheWholeSurfaceIncludingProjectDiscovery() {
        List<String> names = Arrays.stream(PrjxpMcpTool.class.getDeclaredMethods())
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
        tool.grep("run", "legacy", "regex", "java", 5, false, "lines");

        verify(service).grep("run", "legacy", "regex", "java", 5, false, "lines");
    }

    @Test
    void passesTheProjectThroughForVectorSearch() {
        tool.vectorSearch("chunking", "prjxp", 0.8, 3, "all", true, 1000);

        verify(service).vectorSearch("chunking", "prjxp", 0.8, 3, "all", true, 1000);
    }

    @Test
    void passesTheProjectThroughForReadBySignature() {
        tool.readBySignature("a.B", "prjxp", null, true, 2);

        verify(service).readBySignature("a.B", null, "prjxp", true, 2);
    }

    @Test
    void everyToolTakesTheProjectSoOneHubServesManyProjects() {
        for (Method method : PrjxpMcpTool.class.getDeclaredMethods()) {
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
