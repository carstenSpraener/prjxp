package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DocPipeCliApp}. Plain JUnit 5 + Mockito, no Spring context.
 * <p>
 * Only the {@code @Bean commandLineRunner(...)} factory method is tested. The static
 * {@code main()} entry point (Spring Boot bootstrap + dotenv loading) is deliberately
 * left uncovered — it requires a full application context and stays untested by design.
 */
class DocPipeCliAppTest {

    @Test
    void commandLineRunner_returnsRunnerThatParsesArgsAndRunsPipeline() throws Exception {
        PrjXPConfig pxCfg = mock(PrjXPConfig.class);
        DocPipeArgsParser argsParser = mock(DocPipeArgsParser.class);
        DocPipeRunner runner = mock(DocPipeRunner.class);

        CommandLineRunner result = new DocPipeCliApp().commandLineRunner(pxCfg, argsParser, runner);

        assertThat(result).isNotNull();
        // the bean is a lambda: invoking it must delegate to parser and runner in order
        result.run("arg1", "arg2");

        ArgumentCaptor<String[]> argsCaptor = ArgumentCaptor.forClass(String[].class);
        verify(argsParser, times(1)).parseArgs(eq(pxCfg), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("arg1", "arg2");
        verify(runner, times(1)).run(pxCfg);
    }

    @Test
    void commandLineRunner_withEmptyArgs_stillInvokesParserAndRunner() throws Exception {
        PrjXPConfig pxCfg = mock(PrjXPConfig.class);
        DocPipeArgsParser argsParser = mock(DocPipeArgsParser.class);
        DocPipeRunner runner = mock(DocPipeRunner.class);

        CommandLineRunner result = new DocPipeCliApp().commandLineRunner(pxCfg, argsParser, runner);

        result.run();

        verify(argsParser).parseArgs(eq(pxCfg), any(String[].class));
        verify(runner).run(pxCfg);
    }
}
