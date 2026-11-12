package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WebConfigTest {
    @Test
    void administrativeApiExposesVersionAndAllowsConcurrencyHeadersWithoutChangingOrigins() {
        var config = new WebConfig();
        ReflectionTestUtils.setField(config, "allowedPatterns", List.of("http://localhost:*"));
        var registry = new Registry();
        config.addCorsMappings(registry);
        var api = registry.values().get("/api/**");
        assertThat(api.getExposedHeaders()).contains("ETag");
        assertThat(api.getAllowedHeaders()).contains("*");
        assertThat(api.getAllowedOriginPatterns()).containsExactly("http://localhost:*");
        assertThat(api.getAllowCredentials()).isTrue();
    }
    private static class Registry extends CorsRegistry {
        Map<String, CorsConfiguration> values() { return getCorsConfigurations(); }
    }

    @Test
    void setupUsesTheExistingAdministrativeCorsPolicyAlsoWithoutHub() {
        new ApplicationContextRunner().withPropertyValues("prjxp.hub.enabled=false")
                .withUserConfiguration(WebConfig.class)
                .run(context -> assertThat(context).hasSingleBean(WebConfig.class));
    }
}
