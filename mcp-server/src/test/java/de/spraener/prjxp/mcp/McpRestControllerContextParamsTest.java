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
class McpRestControllerContextParamsTest {

    @Mock
    GRPromptEnrichment enrichment;

    @Mock
    ProjectRegistry projectRegistry;

    @InjectMocks
    McpRestController controller;

    @Test
    void noParams_usesLegacyTwoArgEnrich() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q")).thenReturn("legacy context");

        String result = controller.readRelevantSource("q", "default", null, null, null);

        assertThat(result).contains("legacy context");
        verify(enrichment).enrich("myproj", "q");
    }

    @Test
    void explicitParams_useFiveArgEnrich() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.9, 5, true)).thenReturn("tuned context");

        String result = controller.readRelevantSource("q", "default", 0.9, 5, true);

        assertThat(result).contains("tuned context");
        verify(enrichment).enrich("myproj", "q", 0.9, 5, true);
    }

    @Test
    void outOfRangeParams_clamped() {
        when(projectRegistry.resolve("default")).thenReturn("myproj");
        when(enrichment.enrich("myproj", "q", 0.85, 20, false)).thenReturn("clamped context");

        String result = controller.readRelevantSource("q", "default", 1.5, 99, false);

        assertThat(result).contains("clamped context");
        verify(enrichment).enrich("myproj", "q", 0.85, 20, false);
    }

    @Test
    void unknownProject_returnsErrorString() {
        doThrow(new UnknownProjectException("nope", List.of("myproj"))).when(projectRegistry).ensureSearchable("nope");

        String result = controller.readRelevantSource("q", "nope", null, null, null);

        assertThat(result).startsWith("ERROR:").contains("nope");
        verifyNoInteractions(enrichment);
    }
}
