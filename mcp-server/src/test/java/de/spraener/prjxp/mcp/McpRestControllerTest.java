package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpRestControllerTest {

    @Mock
    GRPromptEnrichment enrichment;

    @Mock
    PrjXPConfig cfg;

    @InjectMocks
    McpRestController controller;

    @Test
    void contextEndpointStillReturnsEnrichedString() {
        ProjectDefinition active = new ProjectDefinition();
        active.setName("myproj");
        when(cfg.getActiveProject()).thenReturn(Optional.of(active));
        when(enrichment.enrich("myproj", "How does chunking work?"))
                .thenReturn("Relevante Information aus dem Projekt 'myproj':\nchunk content");

        String result = controller.readRelevantSource("How does chunking work?", "default");

        assertThat(result).contains("chunk content");
        verify(enrichment).enrich("myproj", "How does chunking work?");
    }

    @Test
    void contextWithExplicitProjectSkipsActiveLookup() {
        when(enrichment.enrich("otherproj", "q")).thenReturn("context for otherproj");

        String result = controller.readRelevantSource("q", "otherproj");

        assertThat(result).contains("context for otherproj");
        verify(enrichment).enrich("otherproj", "q");
    }
}
