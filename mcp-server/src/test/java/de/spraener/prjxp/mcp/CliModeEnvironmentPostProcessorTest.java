package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CliModeEnvironmentPostProcessor}. A clean {@link StandardEnvironment}
 * is used (system property/environment sources removed) so the module-wide
 * {@code prjxp.hub.enabled=true} test system property cannot leak in.
 */
class CliModeEnvironmentPostProcessorTest {

    private static final String HUB = "prjxp.hub.enabled";
    private static final String WEB = "spring.main.web-application-type";

    private final CliModeEnvironmentPostProcessor postProcessor = new CliModeEnvironmentPostProcessor();

    /** A clean environment with the given (possibly null) hub / web-application-type values. */
    private StandardEnvironment env(String hub, String web) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove("systemProperties");
        env.getPropertySources().remove("systemEnvironment");
        if (hub != null) {
            env.getPropertySources().addFirst(new MapPropertySource("hub", Map.of(HUB, hub)));
        }
        if (web != null) {
            env.getPropertySources().addFirst(new MapPropertySource("web", Map.of(WEB, web)));
        }
        return env;
    }

    @Test
    void hubModeLeavesWebApplicationTypeUntouched() {
        StandardEnvironment env = env("true", null);
        postProcessor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(WEB)).isNull();
    }

    @Test
    void cliModeForcesNonWebApplication() {
        StandardEnvironment env = env("false", null);
        postProcessor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(WEB)).isEqualTo("none");
    }

    @Test
    void unsetHubDefaultsToNonWebApplication() {
        StandardEnvironment env = env(null, null);
        postProcessor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(WEB)).isEqualTo("none");
    }

    @Test
    void explicitWebApplicationTypeIsNeverOverridden() {
        StandardEnvironment env = env("false", "servlet");
        postProcessor.postProcessEnvironment(env, null);
        assertThat(env.getProperty(WEB)).isEqualTo("servlet");
    }

    @Test
    void runsAfterConfigDataSoItSeesTheYamlValue() {
        assertThat(postProcessor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }
}
