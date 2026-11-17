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
    private HubToolProfile profile;
    private PrjxpP2McpTool tool;

    @BeforeEach
    void setUp() {
        service = mock(PrjxpToolService.class);
        profile = mock(HubToolProfile.class);
        tool = new PrjxpP2McpTool(service, profile);
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
    void bindsTheProjectServerSideForGrep() {
        when(profile.boundProject()).thenReturn("legacy");

        tool.grep("run", "regex", "java", 5, false);

        verify(service).grepP2("run", "legacy", "regex", "java", 5, false);
    }

    @Test
    void bindsTheProjectServerSideForVectorSearch() {
        when(profile.boundProject()).thenReturn("prjxp");

        tool.vectorSearch("chunking", 0.8, 3, "all", true, 1000);

        verify(service).vectorSearchP2("chunking", "prjxp", 0.8, 3, "all", true, 1000);
    }

    @Test
    void bindsTheProjectServerSideForReadBySignature() {
        when(profile.boundProject()).thenReturn("prjxp");

        tool.readBySignature("a.B", null, true, 2);

        verify(service).readBySignatureP2("a.B", null, "prjxp", true, 2);
    }

    @Test
    void toolMethodsDoNotExposeAProjectParameter() {
        for (Method method : PrjxpP2McpTool.class.getDeclaredMethods()) {
            if (method.getAnnotation(McpTool.class) == null) {
                continue;
            }
            assertThat(method.getParameters())
                    .noneMatch(p -> p.getName().equals("project") || p.getName().equals("projectName"));
        }
    }
}
