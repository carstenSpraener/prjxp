package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DocPipeArgsParser}. Plain JUnit 5 + Mockito, no Spring context.
 */
class DocPipeArgsParserTest {

    private Environment env;
    private PxLogService pxLS;
    private DocPipeArgsParser parser;

    @BeforeEach
    void setUp() {
        env = mock(Environment.class);
        pxLS = mock(PxLogService.class);
        parser = new DocPipeArgsParser(env, pxLS);
    }

    @Test
    void parseArgs_shortOption_setsActiveProject() {
        PrjXPConfig cfg = new PrjXPConfig();

        PrjXPConfig result = parser.parseArgs(cfg, new String[]{"-p", "myproject"});

        assertThat(result).isSameAs(cfg);
        assertThat(cfg.getActiveProjectName()).isEqualTo("myproject");
        verify(pxLS, never()).error(any(Throwable.class), anyString());
    }

    @Test
    void parseArgs_longOption_setsActiveProject() {
        PrjXPConfig cfg = new PrjXPConfig();

        PrjXPConfig result = parser.parseArgs(cfg, new String[]{"--project", "myproject"});

        assertThat(result).isSameAs(cfg);
        assertThat(cfg.getActiveProjectName()).isEqualTo("myproject");
        verify(pxLS, never()).error(any(Throwable.class), anyString());
    }

    @Test
    void parseArgs_withoutProjectOption_leavesConfigUntouched() {
        PrjXPConfig cfg = new PrjXPConfig();

        PrjXPConfig result = parser.parseArgs(cfg, new String[]{});

        assertThat(result).isSameAs(cfg);
        // default from PrjXPConfig, never overwritten by the parser
        assertThat(cfg.getActiveProjectName()).isEqualTo("cwd");
        verify(pxLS, never()).error(any(Throwable.class), anyString());
    }

    @Test
    void parseArgs_unknownOption_isSwallowedAndLogged() {
        PrjXPConfig cfg = new PrjXPConfig();

        // unknown option -> ParseException in commons-cli -> RuntimeException,
        // which DocPipeArgsParser deliberately swallows and logs instead of re-throwing
        PrjXPConfig result = parser.parseArgs(cfg, new String[]{"-x"});

        assertThat(result).isSameAs(cfg);
        verify(pxLS).error(any(Throwable.class), anyString());
    }

    @Test
    void parseArgs_optionWithoutValue_isSwallowedAndLogged() {
        PrjXPConfig cfg = new PrjXPConfig();

        // "-p" without its required value -> ParseException ("Insufficient number of arguments")
        PrjXPConfig result = parser.parseArgs(cfg, new String[]{"-p"});

        assertThat(result).isSameAs(cfg);
        verify(pxLS).error(any(Throwable.class), anyString());
    }
}
