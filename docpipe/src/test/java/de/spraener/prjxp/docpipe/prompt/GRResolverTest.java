package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GRResolver}. The golden-retriever dependency
 * ({@link GRPromptEnrichment}) is mocked; the Handlebars flow runs for real
 * via {@link PromptResolvingService} (no Spring context).
 */
class GRResolverTest {

    private final GRPromptEnrichment enrichment = mock(GRPromptEnrichment.class);
    private final PromptResolvingService service = new PromptResolvingService(java.util.List.of(new GRResolver(enrichment)), null);

    @Test
    void idIsGr() {
        assertThat(new GRResolver(enrichment).getID()).isEqualTo("gr");
    }

    @Test
    void delegatesBlockContentAndPrjHashToEnrichment() throws Exception {
        when(enrichment.enrich(anyString(), anyString())).thenReturn("ENRICHED");

        String result = service.resolve(new DPContentCreation(),
                "{{#gr prj=\"myproject\"}}some content{{/gr}}", new File(""));

        assertThat(result).isEqualTo("ENRICHED");
        verify(enrichment).enrich(eq("myproject"), eq("some content"));
    }

    @Test
    void usesDefaultProjectWhenPrjHashMissing() throws Exception {
        when(enrichment.enrich(anyString(), anyString())).thenReturn("ENRICHED");

        String result = service.resolve(new DPContentCreation(),
                "{{#gr}}plain content{{/gr}}", new File(""));

        assertThat(result).isEqualTo("ENRICHED");
        verify(enrichment).enrich(eq("default"), eq("plain content"));
    }

    @Test
    void resolvePassesFnTextAndHashDirectly() throws Exception {
        // Direct unit-level test of resolve(): real Options, mocked Template for fn.
        GRResolver uut = new GRResolver(enrichment);

        com.github.jknack.handlebars.Template fn = mock(com.github.jknack.handlebars.Template.class);
        when(fn.text()).thenReturn("direct content");
        Options options = new Options(new com.github.jknack.handlebars.Handlebars(), "gr",
                com.github.jknack.handlebars.TagType.SECTION,
                com.github.jknack.handlebars.Context.newContext(null),
                fn, null, new Object[0], java.util.Map.of("prj", "directproject"), java.util.List.of());

        when(enrichment.enrich(anyString(), anyString())).thenReturn("DIRECT");

        String result = uut.resolve(new File("."), null, options);

        assertThat(result).isEqualTo("DIRECT");
        verify(enrichment).enrich(eq("directproject"), eq("direct content"));
    }
}
