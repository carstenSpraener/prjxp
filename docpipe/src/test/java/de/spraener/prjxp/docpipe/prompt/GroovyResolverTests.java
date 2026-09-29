package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.common.scripting.ScriptCompileService;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class GroovyResolverTests {

    @Test
    public void testGroovyResolving() throws Exception {
        ScriptCompileService scs = new ScriptCompileService();
        ApplicationContext context = mock(ApplicationContext.class);

        GroovyResolver uut = new GroovyResolver(scs, context);
        PromptResolvingService prs = new PromptResolvingService(List.of(uut), new DotDPFilesService());

        Options optMock = mock(Options.class);

        String result = prs.resolve(mock(DPContentCreation.class), "{{#groovy}}return 'Hello World'{{/groovy}}", new File(""));
        assertEquals("Hello World", result);
    }

    @Test
    public void testBindings() throws Exception {
        ScriptCompileService scs = new ScriptCompileService();
        ApplicationContext context = mock(ApplicationContext.class);

        GroovyResolver uut = new GroovyResolver(scs, context);
        PromptResolvingService prs = new PromptResolvingService(List.of(uut), new DotDPFilesService());

        Options optMock = mock(Options.class);
        String result = prs.resolve(
            mock(DPContentCreation.class),
            """
                {{#groovy name="John"}}
                if( dir==null) {
                    return "No working directory in binding";
                }
                if( applicationContext==null ) {
                    return "No spring context in binding";
                }
                if( options == null ) {
                    return "No handlebars options in binding";
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Working in directory ${dir.getAbsolutePath()}\\n")
                sb.append("The spring context is present\\n")
                sb.append("The handlebars handler options are present.\\n")
                sb.append("Hello to ${options.hash("name")}\\n");
                return sb.toString();
                {{/groovy}}
            """, new File(""));
        assertTrue(result.contains("John"), "value of name not present in options.hash");
    }

}
