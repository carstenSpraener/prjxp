package de.spraener.prjxp.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;

/**
 * Forces a non-web (CLI) application when {@code prjxp.hub.enabled} is not {@code true}.
 * <p>
 * The web/MCP/REST stack is only meaningful in hub mode. In CLI mode the bundled CLI
 * runners execute and the process exits, so no embedded web server should start. This
 * post-processor sets {@code spring.main.web-application-type=none} as a lowest-priority
 * default ({@code addLast}) so that an explicit user setting always wins.
 * <p>
 * It runs just after {@link ConfigDataEnvironmentPostProcessor} (see {@link #getOrder()})
 * so that it observes the effective {@code prjxp.hub.enabled} value, including one that
 * comes from {@code application.yaml}. Registered via {@code META-INF/spring.factories}
 * (merged into the shadow JAR).
 */
public class CliModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String HUB_ENABLED = "prjxp.hub.enabled";
    private static final String WEB_APPLICATION_TYPE = "spring.main.web-application-type";

    /**
     * Run after config data is loaded: this processor reads {@code prjxp.hub.enabled}, which
     * may be defined in {@code application.yaml} and is only present once
     * {@link ConfigDataEnvironmentPostProcessor} has run.
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String hubEnabled = environment.getProperty(HUB_ENABLED);
        if ("true".equalsIgnoreCase(hubEnabled)) {
            return;   // hub mode: the web/MCP/REST stack is expected
        }
        // CLI mode (false or unset): default to a non-web application, but never override
        // an explicit spring.main.web-application-type setting.
        if (environment.getProperty(WEB_APPLICATION_TYPE) == null) {
            environment.getPropertySources()
                    .addLast(new MapPropertySource("prjxpCliModeWebApplicationType",
                            Collections.singletonMap(WEB_APPLICATION_TYPE, "none")));
        }
    }
}
