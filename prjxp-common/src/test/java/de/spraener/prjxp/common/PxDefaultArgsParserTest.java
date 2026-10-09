package de.spraener.prjxp.common;

import de.spraener.prjxp.common.config.PrjXPConfig;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PxDefaultArgsParserTest {

    private Environment env;
    private PxDefaultArgsParser parser;

    @BeforeEach
    void setUp() {
        env = mock(Environment.class);
        parser = new PxDefaultArgsParser(env);
    }

    @Test
    void getOptions_containsProjectOption() {
        Options options = parser.getOptions();

        assertThat(options.hasOption("p")).isTrue();
        Option project = options.getOption("p");
        assertThat(project.getLongOpt()).isEqualTo("project");
        assertThat(project.getArgs()).isEqualTo(1);
    }

    @Test
    void parseArgs_shortForm_setsActiveProject() {
        PrjXPConfig cfg = new PrjXPConfig();

        parser.parseArgs(cfg, new String[]{"-p", "myproj"});

        assertThat(cfg.getActiveProjectName()).isEqualTo("myproj");
    }

    @Test
    void parseArgs_longForm_setsActiveProject() {
        PrjXPConfig cfg = new PrjXPConfig();

        parser.parseArgs(cfg, new String[]{"--project", "myproj"});

        assertThat(cfg.getActiveProjectName()).isEqualTo("myproj");
    }

    @Test
    void parseArgs_unknownTokenThatIsSpringProperty_ignored() {
        when(env.containsProperty("foo")).thenReturn(true);
        PrjXPConfig cfg = new PrjXPConfig();

        assertThatCode(() -> parser.parseArgs(cfg, new String[]{"--foo=bar"}))
                .doesNotThrowAnyException();

        assertThat(cfg.getActiveProjectName()).isEqualTo("cwd");
    }

    @Test
    void parseArgs_unknownTokenWithoutEquals_ignored() {
        when(env.containsProperty("foo")).thenReturn(true);
        PrjXPConfig cfg = new PrjXPConfig();

        assertThatCode(() -> parser.parseArgs(cfg, new String[]{"--foo"}))
                .doesNotThrowAnyException();

        assertThat(cfg.getActiveProjectName()).isEqualTo("cwd");
    }

    @Test
    void parseArgs_unknownTokenNotAProperty_throwsRuntimeException() {
        when(env.containsProperty("foo")).thenReturn(false);
        PrjXPConfig cfg = new PrjXPConfig();

        assertThatThrownBy(() -> parser.parseArgs(cfg, new String[]{"--foo=bar"}))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void handleUnknownToken_springProperty_ignored() {
        when(env.containsProperty("foo")).thenReturn(true);

        assertThatCode(() -> parser.handleUnknownToken("--foo=bar"))
                .doesNotThrowAnyException();
        verify(env).containsProperty("foo");
    }

    @Test
    void handleUnknownToken_notAProperty_delegatesToSuperAndThrowsParseException() {
        when(env.containsProperty("foo")).thenReturn(false);
        // Initialize the parser's internal state (cmd, nonOptionAction=THROW) via a parse round-trip.
        parser.parseArgs(new PrjXPConfig(), new String[]{});

        assertThatThrownBy(() -> parser.handleUnknownToken("--foo=bar"))
                .isInstanceOf(ParseException.class);
        verify(env).containsProperty("foo");
    }

    @Test
    void handleUnknownToken_withoutEqualsSign_stripsHyphensOnly() {
        when(env.containsProperty("foo")).thenReturn(false);
        parser.parseArgs(new PrjXPConfig(), new String[]{});

        assertThatThrownBy(() -> parser.handleUnknownToken("--foo"))
                .isInstanceOf(ParseException.class);
        verify(env).containsProperty("foo");
    }
}
