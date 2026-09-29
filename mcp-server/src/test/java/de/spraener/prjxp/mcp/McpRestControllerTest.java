package de.spraener.prjxp.mcp;

import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpRestControllerTest {

    @Mock
    GRPromptEnrichment enrichment;

    @Mock
    ProjectRegistry projectRegistry;

    @InjectMocks
    McpRestController controller;

    @Test
    void contextEndpointStillReturnsEnrichedString() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "How does chunking work?"))
                .thenReturn("Relevante Information aus dem Projekt 'myproj':\nchunk content");

        String result = controller.readRelevantSource("How does chunking work?", "default");

        assertThat(result).contains("chunk content");
        verify(enrichment).enrich("myproj", "How does chunking work?");
    }

    @Test
    void contextWithExplicitProjectSkipsActiveLookup() {
        when(projectRegistry.resolve("otherproj")).thenReturn("otherproj");
        when(enrichment.enrich("otherproj", "q")).thenReturn("context for otherproj");

        String result = controller.readRelevantSource("q", "otherproj");

        assertThat(result).contains("context for otherproj");
        verify(enrichment).enrich("otherproj", "q");
    }

    @Test
    void contextWithUnknownProjectReturnsErrorString() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        String result = controller.readRelevantSource("q", "nope");

        assertThat(result).startsWith("ERROR:").contains("nope").contains("alpha");
        verifyNoInteractions(enrichment);
    }

    @Test
    void projectsEndpointReturnsProjectInfosWithLifecycleStatus() {
        when(projectRegistry.projectInfos()).thenReturn(List.of(
                new ProjectInfo("alpha", "READY", null),
                new ProjectInfo("beta", "CHUNKING", null)));

        assertThat(controller.listProjects()).containsExactly(
                new ProjectInfo("alpha", "READY", null),
                new ProjectInfo("beta", "CHUNKING", null));
    }
}
